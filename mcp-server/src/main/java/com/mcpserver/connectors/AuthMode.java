package com.mcpserver.connectors;

/**
 * OAUTH2 is a reserved extension point — not implemented. BASIC (username + password/API-token)
 * is used by the Atlassian connectors; NONE/BEARER/API_KEY_HEADER cover API_COLLECTION
 * connections (for API_KEY_HEADER the header name is stored in {@code authUsername} and the key
 * in {@code authSecretEncrypted}).
 */
public enum AuthMode {
    BASIC,
    OAUTH2,
    NONE,
    BEARER,
    API_KEY_HEADER
}
