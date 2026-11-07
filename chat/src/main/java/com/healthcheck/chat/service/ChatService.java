package com.healthcheck.chat.service;

import com.healthcheck.chat.ai.OpenAiParser;
import com.healthcheck.chat.ai.ParsedRequest;
import com.healthcheck.chat.aws.EcsGateway;
import com.healthcheck.chat.aws.ServiceState;
import com.healthcheck.chat.validation.Change;
import com.healthcheck.chat.validation.RejectedRequestException;
import com.healthcheck.chat.validation.RequestValidator;
import com.healthcheck.chat.web.ChatReply;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.UnauthorizedException;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;

/**
 * The chat flow for one message:
 * 1. read the current service state  2. ask OpenAI what the user wants  3. validate it
 * 4. apply it with the AWS SDK       5. wait until ECS is stable and report the result.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final String EXAMPLES = """
            Things you can ask, for example:
            • scale to 3
            • set memory to 1024 MB  /  use 0.5 vCPU and 2 GB
            • set APP_MESSAGE to Good morning
            • remove env var APP_MESSAGE
            • deploy image tag v1
            • what's running?""";

    private static final int MAX_MESSAGE_LENGTH = 500;

    private final EcsGateway ecs;
    private final OpenAiParser parser;
    private final RequestValidator validator;

    public ChatService(EcsGateway ecs, OpenAiParser parser, RequestValidator validator) {
        this.ecs = ecs;
        this.parser = parser;
        this.validator = validator;
    }

    /** synchronized: one change at a time, so two requests can't overwrite each other's task definition. */
    public synchronized ChatReply handle(String message) {
        if (message == null || message.isBlank()) {
            return ChatReply.unclear("Please type a request.\n\n" + EXAMPLES);
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return ChatReply.unclear("That message is too long (max " + MAX_MESSAGE_LENGTH + " characters).");
        }

        try {
            ServiceState before = ecs.readState();                     // 1
            ParsedRequest parsed = parser.parse(message, before);      // 2
            Change change = validator.validate(parsed, before);        // 3
            return apply(change, before);                              // 4 + 5

        } catch (RejectedRequestException e) {
            return ChatReply.unclear(e.getMessage() + "\n\n" + EXAMPLES);

        } catch (UnauthorizedException e) {
            return ChatReply.error("The OpenAI API rejected the request as unauthorized. "
                    + "Is the OPENAI_API_KEY environment variable set (in the terminal that started this app) and valid?");
        } catch (NotFoundException e) {
            log.error("OpenAI model not found", e);
            return ChatReply.error("The OpenAI model wasn't found or your key can't use it. "
                    + "Set a model you have access to with the OPENAI_MODEL environment variable.");
        } catch (OpenAIServiceException e) {
            log.error("OpenAI API error", e);
            return ChatReply.error("The OpenAI API returned an error (HTTP " + e.statusCode() + "). Please try again.");
        } catch (OpenAIException e) {
            log.error("OpenAI API call failed", e);
            return ChatReply.error("Couldn't reach the OpenAI API: " + e.getMessage());

        } catch (SdkClientException e) {
            log.error("AWS SDK client error", e);
            return ChatReply.error("Couldn't call AWS: " + e.getMessage()
                    + "\nIf your AWS session expired, run `aws login` and try again.");
        } catch (AwsServiceException e) {
            log.error("AWS error", e);
            return ChatReply.error("AWS refused the request: " + e.awsErrorDetails().errorMessage()
                    + "\nIf your AWS session expired, run `aws login` and try again.");

        } catch (IllegalStateException e) {
            return ChatReply.error(e.getMessage());
        }
    }

    private ChatReply apply(Change change, ServiceState before) {
        log.info("Applying {}", change);

        if (change instanceof Change.Status) {
            return ChatReply.info("Current status:\n" + before.describe());
        }
        if (change instanceof Change.NoChange noChange) {
            return ChatReply.info(noChange.message());
        }
        if (change instanceof Change.Scale scale) {
            ecs.scale(scale.desiredCount());
            return waitAndReport("Scaled from " + before.desiredCount() + " to " + scale.desiredCount() + " task(s).");
        }
        if (change instanceof Change.Resources res) {
            ecs.deployNewRevision(res.cpu(), res.memory(), container -> container);
            return waitAndReport("Changed CPU " + before.cpu() + " → " + res.cpu()
                    + " and memory " + before.memory() + " → " + res.memory() + " MB.");
        }
        if (change instanceof Change.SetEnv env) {
            ecs.deployNewRevision(before.cpu(), before.memory(),
                    container -> withEnvironment(container, env.name(), env.value()));
            return waitAndReport("Set " + env.name() + " = \"" + env.value() + "\".");
        }
        if (change instanceof Change.RemoveEnv env) {
            ecs.deployNewRevision(before.cpu(), before.memory(),
                    container -> withEnvironment(container, env.name(), null));
            return waitAndReport("Removed environment variable " + env.name() + ".");
        }
        if (change instanceof Change.ImageTag image) {
            if (!ecs.imageTagExists(before.image(), image.tag())) {
                return ChatReply.unclear("Image tag " + image.tag() + " doesn't exist in ECR. Available tags: "
                        + String.join(", ", ecs.availableImageTags(before.image())) + ".");
            }
            ecs.deployNewRevision(before.cpu(), before.memory(),
                    container -> container.toBuilder().image(EcsGateway.withTag(container.image(), image.tag())).build());
            return waitAndReport("Switched image tag " + before.imageTag() + " → " + image.tag() + ".");
        }
        throw new IllegalStateException("Unknown change: " + change);
    }

    /** Returns a copy of the container with one env var set (or removed when value is null). */
    private static ContainerDefinition withEnvironment(ContainerDefinition container, String name, String value) {
        List<KeyValuePair> env = new ArrayList<>();
        for (KeyValuePair pair : container.environment()) {
            if (!pair.name().equals(name)) {
                env.add(pair);
            }
        }
        if (value != null) {
            env.add(KeyValuePair.builder().name(name).value(value).build());
        }
        return container.toBuilder().environment(env).build();
    }

    private ChatReply waitAndReport(String whatChanged) {
        boolean stable = ecs.waitUntilStable();
        ServiceState after = ecs.readState();

        if (stable) {
            return ChatReply.success("✅ " + whatChanged + " ECS has finished the change.\n\n" + after.describe());
        }
        return ChatReply.error("⚠️ " + whatChanged + " The change was sent to ECS, but the service did not become "
                + "stable in time. It may still be in progress, or the new tasks may be failing.\n\n"
                + after.describe() + "\n\nRecent ECS events:\n• " + String.join("\n• ", ecs.recentEvents(3)));
    }
}
