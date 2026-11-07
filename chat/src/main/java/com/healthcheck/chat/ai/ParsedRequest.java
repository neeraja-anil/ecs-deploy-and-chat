package com.healthcheck.chat.ai;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * What the OpenAI model must return for every user message. The OpenAI SDK turns this record into a
 * JSON schema, and the API guarantees the reply matches it (structured outputs).
 * Optional fields may be null; that keeps "scale to 0" (0) different from "no number given" (null).
 *
 * This is only a suggestion from the model: RequestValidator checks every value before use.
 */
public record ParsedRequest(
        @JsonPropertyDescription("The one action that matches the request")
        Action action,

        @JsonPropertyDescription("SCALE only: number of tasks to run")
        Optional<Integer> desiredCount,

        @JsonPropertyDescription("SET_RESOURCES only: CPU units per task (1024 = 1 vCPU), or null if not mentioned")
        Optional<Integer> cpu,

        @JsonPropertyDescription("SET_RESOURCES only: memory per task in MB (1 GB = 1024), or null if not mentioned")
        Optional<Integer> memory,

        @JsonPropertyDescription("SET_ENV / REMOVE_ENV only: environment variable name")
        Optional<String> envName,

        @JsonPropertyDescription("SET_ENV only: environment variable value")
        Optional<String> envValue,

        @JsonPropertyDescription("SET_IMAGE_TAG only: image tag, e.g. v2")
        Optional<String> imageTag,

        @JsonPropertyDescription("UNCLEAR only: short explanation for the user")
        Optional<String> reason) {

    /** The fixed list of things the chat can do. */
    public enum Action {
        SCALE,
        SET_RESOURCES,
        SET_ENV,
        REMOVE_ENV,
        SET_IMAGE_TAG,
        STATUS,
        UNCLEAR
    }
}
