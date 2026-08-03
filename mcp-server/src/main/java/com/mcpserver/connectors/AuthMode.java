package com.mcpserver.connectors;

/**
 * OAUTH2 is a reserved extension point — not implemented. Atlassian connections use BASIC for
 * Cloud email/API-token or Server/DC username/password, and BEARER for Server/DC personal access
 * tokens. NONE/BEARER/API_KEY_HEADER cover API_COLLECTION connections (for API_KEY_HEADER the
 * header name is stored in {@code authUsername} and the key in {@code authSecretEncrypted}).
 */
public enum AuthMode {
    BASIC,
    OAUTH2,
    NONE,
    BEARER,
    API_KEY_HEADER
}
