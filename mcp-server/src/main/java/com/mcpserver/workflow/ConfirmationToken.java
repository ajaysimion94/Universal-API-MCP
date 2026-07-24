package com.mcpserver.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class ConfirmationToken {
    private ConfirmationToken() {}

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    public static Instant expiresAt(Duration ttl) {
        return Instant.now().plus(ttl);
    }

    public static boolean isValid(String token, Instant expiresAt) {
        return token != null && expiresAt != null && Instant.now().isBefore(expiresAt);
    }
}
