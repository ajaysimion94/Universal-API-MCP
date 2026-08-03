package com.mcpserver.connectors;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Shared authorization-header builder for Confluence and Jira. */
public final class AtlassianAuth {

    private AtlassianAuth() {}

    public static String basicAuthHeader(String username, String password) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required for Atlassian Basic authentication");
        }
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Atlassian Cloud API tokens use Basic auth ({@code email:token}); Jira and Confluence Data
     * Center personal access tokens use Bearer auth. Keeping the choice explicit avoids guessing
     * from a secret's shape and also continues to support Server/DC username/password installs.
     */
    public static String authorizationHeader(AuthMode authMode, String username, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Password or token is required for Atlassian authentication");
        }
        return switch (authMode) {
            case BASIC -> basicAuthHeader(username, secret);
            case BEARER -> "Bearer " + secret;
            default -> throw new IllegalArgumentException(
                    "Atlassian connections support BASIC or BEARER authentication, not " + authMode);
        };
    }
}
