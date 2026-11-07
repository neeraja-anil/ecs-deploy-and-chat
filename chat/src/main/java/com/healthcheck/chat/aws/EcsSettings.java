package com.healthcheck.chat.aws;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which ECS service to control. Values come from application.yml (the "ecs:" section),
 * which can be overridden with environment variables. They must match the Terraform outputs.
 */
@ConfigurationProperties(prefix = "ecs")
public record EcsSettings(
        String region,
        String cluster,
        String service,
        int waitTimeoutMinutes,
        int maxTasks) {
}
