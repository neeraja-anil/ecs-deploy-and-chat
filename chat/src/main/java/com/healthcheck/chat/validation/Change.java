package com.healthcheck.chat.validation;

/**
 * A change that has passed validation and is safe to apply. Created only by RequestValidator.
 */
public sealed interface Change {

    record Scale(int desiredCount) implements Change {}

    record Resources(int cpu, int memory) implements Change {}

    record SetEnv(String name, String value) implements Change {}

    record RemoveEnv(String name) implements Change {}

    record ImageTag(String tag) implements Change {}

    record Status() implements Change {}

    /** The request is valid but asks for what is already in place. */
    record NoChange(String message) implements Change {}
}
