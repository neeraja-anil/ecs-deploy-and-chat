package com.healthcheck.chat.validation;

/**
 * The request is unclear or asks for something that isn't allowed.
 * The message is shown to the user as-is, so it should say what to do instead.
 */
public class RejectedRequestException extends RuntimeException {

    public RejectedRequestException(String message) {
        super(message);
    }
}
