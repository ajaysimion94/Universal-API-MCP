package com.mcpserver.connectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class WebhookTokenServiceTests {

    @Autowired
    private WebhookTokenService webhookTokenService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void tokenIsPersistentEncryptedAndComparedWithoutPlaintextStorage() {
        String connectionId = "webhook-" + UUID.randomUUID();

        String first = webhookTokenService.getOrCreate(connectionId);
        String second = webhookTokenService.getOrCreate(connectionId);
        String stored = jdbc.queryForObject(
                "SELECT token_encrypted FROM connection_webhook_tokens WHERE connection_id = ?",
                String.class, connectionId);

        assertThat(first).isEqualTo(second).hasSizeGreaterThan(40);
        assertThat(stored).isNotEqualTo(first).doesNotContain(first);
        assertThat(webhookTokenService.verify(connectionId, first)).isTrue();
        assertThat(webhookTokenService.verify(connectionId, "wrong-token")).isFalse();
        assertThat(webhookTokenService.verify(connectionId, null)).isFalse();

        webhookTokenService.delete(connectionId);
        assertThat(webhookTokenService.verify(connectionId, first)).isFalse();
    }
}
