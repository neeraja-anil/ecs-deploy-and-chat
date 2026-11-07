package com.healthcheck;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoints of the service.
 */
@RestController
public class AppController {

    /**
     * Health check.
     * HTTP 200 means "this container is working, send it traffic".
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /**
     * Status page, so changes to the running service are visible:
     *  - message:  from env var APP_MESSAGE
     */
    @GetMapping("/")
    public Map<String, String> info() {
        Map<String, String> body = Map.of("message", System.getenv().getOrDefault("APP_MESSAGE", "Hi, Welcome!"));
        return body;
    }
}
