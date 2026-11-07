package com.healthcheck.chat.validation;

import com.healthcheck.chat.ai.ParsedRequest;
import com.healthcheck.chat.aws.EcsSettings;
import com.healthcheck.chat.aws.ServiceState;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Step 3: checks what the AI model returned before anything touches AWS.
 * The model only suggests; this class decides. Anything outside the rules is rejected
 * with a message that tells the user what would work.
 */
@Component
public class RequestValidator {

    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,99}");
    private static final Pattern IMAGE_TAG = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");
    private static final int MAX_ENV_VALUE_LENGTH = 500;

    /** Highest task count allowed; from ecs.max-tasks in application.yml (env var ECS_MAX_TASKS). */
    private final int maxTasks;

    public RequestValidator(EcsSettings settings) {
        this.maxTasks = settings.maxTasks();
    }

    public Change validate(ParsedRequest request, ServiceState state) {
        if (request == null || request.action() == null) {
            throw new RejectedRequestException("I couldn't interpret that request.");
        }

        return switch (request.action()) {
            case SCALE -> scale(request, state);
            case SET_RESOURCES -> resources(request, state);
            case SET_ENV -> setEnv(request, state);
            case REMOVE_ENV -> removeEnv(request, state);
            case SET_IMAGE_TAG -> imageTag(request, state);
            case STATUS -> new Change.Status();
            case UNCLEAR -> throw new RejectedRequestException(
                    request.reason().orElse("I couldn't interpret that request."));
        };
    }

    private Change scale(ParsedRequest request, ServiceState state) {
        int count = request.desiredCount()
                .orElseThrow(() -> new RejectedRequestException("How many tasks should run? For example: \"scale to 2\"."));
        if (count < 0 || count > maxTasks) {
            throw new RejectedRequestException(
                    "I can only scale between 0 and " + maxTasks + " tasks (you asked for " + count + ").");
        }
        if (count == state.desiredCount()) {
            return new Change.NoChange("The service is already set to " + count + " task(s). Nothing to change.");
        }
        return new Change.Scale(count);
    }

    private Change resources(ParsedRequest request, ServiceState state) {
        if (request.cpu().isEmpty() && request.memory().isEmpty()) {
            throw new RejectedRequestException("Which CPU or memory size? For example: \"set memory to 1024 MB\".");
        }
        int cpu = request.cpu().orElse(state.cpu());
        int memory = request.memory().orElse(state.memory());

        if (!FargateSizes.isValid(cpu, memory)) {
            throw new RejectedRequestException(
                    "Fargate doesn't support CPU " + cpu + " with " + memory + " MB memory. "
                            + FargateSizes.allowedFor(cpu));
        }
        if (cpu == state.cpu() && memory == state.memory()) {
            return new Change.NoChange("The service already uses CPU " + cpu + " and " + memory + " MB memory. Nothing to change.");
        }
        return new Change.Resources(cpu, memory);
    }

    private Change setEnv(ParsedRequest request, ServiceState state) {
        String name = envName(request);
        String value = request.envValue()
                .orElseThrow(() -> new RejectedRequestException("What value should " + name + " have? For example: \"set " + name + " to hello\"."));
        if (value.length() > MAX_ENV_VALUE_LENGTH) {
            throw new RejectedRequestException("That value is too long (max " + MAX_ENV_VALUE_LENGTH + " characters).");
        }
        if (value.equals(state.environment().get(name))) {
            return new Change.NoChange(name + " already has that value. Nothing to change.");
        }
        return new Change.SetEnv(name, value);
    }

    private Change removeEnv(ParsedRequest request, ServiceState state) {
        String name = envName(request);
        if (!state.environment().containsKey(name)) {
            String existing = state.environment().isEmpty() ? "none" : String.join(", ", state.environment().keySet());
            throw new RejectedRequestException("There is no environment variable " + name + ". Current variables: " + existing + ".");
        }
        return new Change.RemoveEnv(name);
    }

    private String envName(ParsedRequest request) {
        String name = request.envName()
                .orElseThrow(() -> new RejectedRequestException("Which environment variable? For example: \"set APP_MESSAGE to hello\"."));
        if (!ENV_NAME.matcher(name).matches()) {
            throw new RejectedRequestException(
                    "\"" + name + "\" isn't a valid variable name. Use letters, digits and _ (for example APP_MESSAGE).");
        }
        String upper = name.toUpperCase();
        if (upper.startsWith("AWS_") || upper.startsWith("ECS_")) {
            throw new RejectedRequestException("Variables starting with AWS_ or ECS_ are reserved and can't be changed here.");
        }
        return name;
    }

    private Change imageTag(ParsedRequest request, ServiceState state) {
        String tag = request.imageTag()
                .orElseThrow(() -> new RejectedRequestException("Which image tag? For example: \"deploy image tag v2\"."));
        if (!IMAGE_TAG.matcher(tag).matches()) {
            throw new RejectedRequestException("\"" + tag + "\" isn't a valid image tag.");
        }
        if (tag.equals(state.imageTag())) {
            return new Change.NoChange("The service already runs image tag " + tag + ". Nothing to change.");
        }
        return new Change.ImageTag(tag);
    }

    /** CPU/memory combinations Fargate accepts (memory in MB). */
    static final class FargateSizes {

        static boolean isValid(int cpu, int memory) {
            return switch (cpu) {
                case 256 -> memory == 512 || memory == 1024 || memory == 2048;
                case 512 -> wholeGbBetween(memory, 1024, 4096);
                case 1024 -> wholeGbBetween(memory, 2048, 8192);
                case 2048 -> wholeGbBetween(memory, 4096, 16384);
                case 4096 -> wholeGbBetween(memory, 8192, 30720);
                default -> false;
            };
        }

        static String allowedFor(int cpu) {
            return switch (cpu) {
                case 256 -> "With CPU 256, memory can be 512, 1024 or 2048 MB.";
                case 512 -> "With CPU 512, memory can be 1024 to 4096 MB in 1024 MB steps.";
                case 1024 -> "With CPU 1024, memory can be 2048 to 8192 MB in 1024 MB steps.";
                case 2048 -> "With CPU 2048, memory can be 4096 to 16384 MB in 1024 MB steps.";
                case 4096 -> "With CPU 4096, memory can be 8192 to 30720 MB in 1024 MB steps.";
                default -> "CPU must be 256, 512, 1024, 2048 or 4096 (1024 = 1 vCPU).";
            };
        }

        private static boolean wholeGbBetween(int memory, int min, int max) {
            return memory >= min && memory <= max && memory % 1024 == 0;
        }
    }
}
