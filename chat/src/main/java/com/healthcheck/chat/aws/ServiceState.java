package com.healthcheck.chat.aws;

import java.util.Map;

/**
 * Snapshot of the ECS service and the task definition it currently runs.
 */
public record ServiceState(
        int desiredCount,
        int runningCount,
        int cpu,
        int memory,
        String image,
        String taskDefinitionArn,
        Map<String, String> environment) {

    /** The part of the image after the last ':' (e.g. "v1"). */
    public String imageTag() {
        int slash = image.lastIndexOf('/');
        int colon = image.lastIndexOf(':');
        return colon > slash ? image.substring(colon + 1) : "latest";
    }

    /** The task definition revision number (the part after the last ':'). */
    public String revision() {
        return taskDefinitionArn.substring(taskDefinitionArn.lastIndexOf(':') + 1);
    }

    /** Human-readable summary shown for the "status" action and after changes. */
    public String describe() {
        String env = environment.isEmpty() ? "(none)" : String.join(", ", environment.keySet());
        return """
                Tasks: %d running / %d desired
                CPU: %d units (%.2f vCPU), memory: %d MB
                Image tag: %s
                Task definition revision: %s
                Environment variables: %s"""
                .formatted(runningCount, desiredCount, cpu, cpu / 1024.0, memory, imageTag(), revision(), env);
    }
}
