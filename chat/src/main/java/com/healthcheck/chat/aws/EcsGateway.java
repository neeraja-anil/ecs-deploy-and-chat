package com.healthcheck.chat.aws;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.waiters.WaiterOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.ImageNotFoundException;
import software.amazon.awssdk.services.ecr.model.TagStatus;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.ServiceEvent;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.ecs.waiters.EcsWaiter;

/**
 * Everything that talks to AWS. Uses the AWS SDK's default credential chain,
 * which picks up the credentials from `aws login` (default profile).
 */
@Component
public class EcsGateway {

    private final EcsSettings settings;
    private final EcsClient ecs;
    private final EcrClient ecr;

    public EcsGateway(EcsSettings settings) {
        this.settings = settings;
        Region region = Region.of(settings.region());
        this.ecs = EcsClient.builder().region(region).build();
        this.ecr = EcrClient.builder().region(region).build();
    }

    // ---------- read ----------

    public ServiceState readState() {
        Service service = describeService();
        TaskDefinition taskDef = describeTaskDefinition(service.taskDefinition());
        ContainerDefinition container = taskDef.containerDefinitions().get(0);

        Map<String, String> env = new TreeMap<>(); // sorted by name, for stable output
        for (KeyValuePair pair : container.environment()) {
            env.put(pair.name(), pair.value());
        }

        return new ServiceState(
                service.desiredCount(),
                service.runningCount(),
                Integer.parseInt(taskDef.cpu()),
                Integer.parseInt(taskDef.memory()),
                container.image(),
                taskDef.taskDefinitionArn(),
                env);
    }

    /** The latest few service events (newest first), useful when a deployment doesn't settle. */
    public List<String> recentEvents(int count) {
        return describeService().events().stream()
                .limit(count)
                .map(ServiceEvent::message)
                .toList();
    }

    /** Image tags that exist in the ECR repository the service's image comes from. */
    public List<String> availableImageTags(String image) {
        return ecr.listImages(r -> r.repositoryName(repositoryName(image)).filter(f -> f.tagStatus(TagStatus.TAGGED)))
                .imageIds().stream()
                .map(id -> id.imageTag())
                .sorted()
                .toList();
    }

    public boolean imageTagExists(String image, String tag) {
        try {
            ecr.describeImages(r -> r.repositoryName(repositoryName(image)).imageIds(id -> id.imageTag(tag)));
            return true;
        } catch (ImageNotFoundException e) {
            return false;
        }
    }

    // ---------- change ----------

    /** Change how many tasks run. No new task definition needed. */
    public void scale(int desiredCount) {
        ecs.updateService(r -> r.cluster(settings.cluster()).service(settings.service()).desiredCount(desiredCount));
    }

    /**
     * Task definitions can't be edited, so: copy the current one with the requested change,
     * register it as a new revision, and point the service at it. ECS then replaces the tasks.
     *
     * @return the new task definition revision ARN
     */
    public String deployNewRevision(int cpu, int memory, UnaryOperator<ContainerDefinition> changeContainer) {
        TaskDefinition current = describeTaskDefinition(describeService().taskDefinition());
        ContainerDefinition container = changeContainer.apply(current.containerDefinitions().get(0));

        RegisterTaskDefinitionRequest request = RegisterTaskDefinitionRequest.builder()
                .family(current.family())
                .taskRoleArn(current.taskRoleArn())
                .executionRoleArn(current.executionRoleArn())
                .networkMode(current.networkMode())
                .requiresCompatibilities(current.requiresCompatibilities())
                .runtimePlatform(current.runtimePlatform())
                .volumes(current.volumes())
                .cpu(String.valueOf(cpu))
                .memory(String.valueOf(memory))
                .containerDefinitions(container)
                .build();

        String newArn = ecs.registerTaskDefinition(request).taskDefinition().taskDefinitionArn();
        ecs.updateService(r -> r.cluster(settings.cluster()).service(settings.service()).taskDefinition(newArn));
        return newArn;
    }

    /**
     * Blocks until ECS reports the service stable (one deployment, running == desired),
     * or the timeout passes.
     *
     * @return true if stable, false on timeout
     */
    public boolean waitUntilStable() {
        try (EcsWaiter waiter = EcsWaiter.builder()
                .client(ecs)
                .overrideConfiguration(WaiterOverrideConfiguration.builder()
                        .waitTimeout(Duration.ofMinutes(settings.waitTimeoutMinutes()))
                        .build())
                .build()) {
            return waiter.waitUntilServicesStable(r -> r.cluster(settings.cluster()).services(settings.service()))
                    .matched().response().isPresent();
        } catch (software.amazon.awssdk.core.exception.SdkClientException e) {
            // thrown when the waiter gives up (timeout / max attempts)
            return false;
        }
    }

    // ---------- helpers ----------

    private Service describeService() {
        List<Service> services = ecs.describeServices(r -> r.cluster(settings.cluster()).services(settings.service()))
                .services();
        if (services.isEmpty() || !"ACTIVE".equals(services.get(0).status())) {
            throw new IllegalStateException("ECS service " + settings.service() + " was not found in cluster "
                    + settings.cluster() + " (region " + settings.region() + "). Is the infrastructure deployed?");
        }
        return services.get(0);
    }

    private TaskDefinition describeTaskDefinition(String arn) {
        return ecs.describeTaskDefinition(r -> r.taskDefinition(arn)).taskDefinition();
    }

    /** "123.dkr.ecr.ap-south-1.amazonaws.com/ecs-health-check-app:v1" -> "ecs-health-check-app" */
    public static String repositoryName(String image) {
        String withoutRegistry = image.substring(image.indexOf('/') + 1);
        int colon = withoutRegistry.lastIndexOf(':');
        return colon >= 0 ? withoutRegistry.substring(0, colon) : withoutRegistry;
    }

    /** Replaces the tag part of an image reference. */
    public static String withTag(String image, String tag) {
        int slash = image.lastIndexOf('/');
        int colon = image.lastIndexOf(':');
        String base = colon > slash ? image.substring(0, colon) : image;
        return base + ":" + tag;
    }
}
