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
     * Shows the current configuration so changes made through the chat are visible:
     *  - message:  from env var APP_MESSAGE
     *  - hostname: differs per container, so after scaling up, refreshing
     *              shows requests landing on different containers
     *  - config:   every env var starting with APP_
     */
    @GetMapping("/")
    public Map<String, Object> info() {
        Map<String, Object> body = new HashMap<>();
        body.put("message", System.getenv().getOrDefault("APP_MESSAGE", "Hi, Welcome!"));
        body.put("hostname", hostname());
        body.put("config", appEnvVars());
        return body;
    }

    private static Map<String, String> appEnvVars() {
        Map<String, String> vars = new HashMap<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (entry.getKey().startsWith("APP_")) {
                vars.put(entry.getKey(), entry.getValue());
            }
        }
        return vars;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
