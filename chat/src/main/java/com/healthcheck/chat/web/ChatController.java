package com.healthcheck.chat.web;

import com.healthcheck.chat.service.ChatService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The page (src/main/resources/static/index.html) sends each message here.
 * Changes can take 1-3 minutes, because the reply is sent only after ECS has finished.
 */
@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/api/chat")
    public ChatReply chat(@RequestBody ChatRequest request) {
        return chatService.handle(request.message());
    }

    public record ChatRequest(String message) {
    }
}
