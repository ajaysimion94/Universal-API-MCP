package com.mcpserver.connectors;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Shared HTTP Basic Auth header builder for Confluence and Jira. Cloud's "password" is actually
 * an API token — Atlassian's own supported pattern is Basic auth with {@code email:token} — which
 * is functionally identical over this header to a Server/DC password or Personal Access Token, so
 * both deployment types use the same header construction.
 */
public final class AtlassianAuth {

    private AtlassianAuth() {}

    public static String basicAuthHeader(String username, String password) {
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
