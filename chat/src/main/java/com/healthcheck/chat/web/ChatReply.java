package com.healthcheck.chat.web;

/**
 * What the page receives for each message. "type" decides the colour in the chat:
 * success (green), info (blue), unclear (amber), error (red).
 */
public record ChatReply(String type, String text) {

    public static ChatReply success(String text) {
        return new ChatReply("success", text);
    }

    public static ChatReply info(String text) {
        return new ChatReply("info", text);
    }

    public static ChatReply unclear(String text) {
        return new ChatReply("unclear", text);
    }

    public static ChatReply error(String text) {
        return new ChatReply("error", text);
    }
}
