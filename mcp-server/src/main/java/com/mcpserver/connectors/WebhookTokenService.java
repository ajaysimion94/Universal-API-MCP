package com.mcpserver.connectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/** Creates and verifies persistent, per-connection webhook callback tokens. */
@Service
public class WebhookTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final CredentialCipher credentialCipher;

    public WebhookTokenService(JdbcTemplate jdbc, CredentialCipher credentialCipher) {
        this.jdbc = jdbc;
        this.credentialCipher = credentialCipher;
    }

    public String getOrCreate(String connectionId) {
        List<String> encrypted = jdbc.queryForList(
                "SELECT token_encrypted FROM connection_webhook_tokens WHERE connection_id = ?",
                String.class, connectionId);
        if (!encrypted.isEmpty()) return credentialCipher.decrypt(encrypted.get(0));

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("""
                INSERT OR IGNORE INTO connection_webhook_tokens (connection_id, token_encrypted)
                VALUES (?, ?)
                """, connectionId, credentialCipher.encrypt(token));

        // Another caller may have won the insert; always return the persisted value.
        return credentialCipher.decrypt(jdbc.queryForObject(
                "SELECT token_encrypted FROM connection_webhook_tokens WHERE connection_id = ?",
                String.class, connectionId));
    }

    public boolean verify(String connectionId, String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) return false;
        List<String> encrypted = jdbc.queryForList(
                "SELECT token_encrypted FROM connection_webhook_tokens WHERE connection_id = ?",
                String.class, connectionId);
        if (encrypted.isEmpty()) return false;
        byte[] expected = credentialCipher.decrypt(encrypted.get(0)).getBytes(StandardCharsets.UTF_8);
        byte[] presented = presentedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented);
    }

    public void delete(String connectionId) {
        jdbc.update("DELETE FROM connection_webhook_tokens WHERE connection_id = ?", connectionId);
    }
}
