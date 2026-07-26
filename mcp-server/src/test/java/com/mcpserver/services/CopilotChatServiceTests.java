package com.mcpserver.services;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Credential-management paths of {@link CopilotChatService} that don't touch the network.
 * (Validation itself is a live handshake and can't run in a unit test.)
 */
class CopilotChatServiceTests {

    private static CopilotChatService service() {
        // SearchService is only used for grounding retrieval, never on these paths.
        return new CopilotChatService(null, 30, 5, "smart", 6, 1500, "", "", "");
    }

    @Test
    void startsUnconfiguredWhenNoEnvCredentials() {
        Map<String, Object> status = service().credentialStatus(true);

        assertThat(status.get("configured")).isEqualTo(false);
        assertThat(status.get("source")).isEqualTo("none");
        assertThat(status.get("validated")).isEqualTo(false);
        assertThat(status.get("message").toString()).contains("Not configured");
    }

    @Test
    void envCredentialsReportEnvSource() {
        CopilotChatService s = new CopilotChatService(null, 30, 5, "smart", 6, 1500,
                "token-abc", "", "");

        Map<String, Object> status = s.credentialStatus(true);

        assertThat(status.get("configured")).isEqualTo(true);
        assertThat(status.get("source")).isEqualTo("env");
        assertThat(status.get("validated")).isEqualTo(false); // validated on first use, not at boot
    }

    @Test
    void updateAndValidateRejectsBlankInputBeforeAnyNetworkCall() {
        assertThatThrownBy(() -> service().updateAndValidate("  ", null, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessToken");
    }

    @Test
    void clearDropsBackToAnonymous() {
        CopilotChatService s = new CopilotChatService(null, 30, 5, "smart", 6, 1500,
                "token-abc", "", "");

        Map<String, Object> status = s.clearCredentials();

        assertThat(status.get("configured")).isEqualTo(false);
        assertThat(status.get("source")).isEqualTo("none");
        assertThat(status.get("validated")).isEqualTo(false);
    }

    @Test
    void statusNeverLeaksTheSecret() {
        CopilotChatService s = new CopilotChatService(null, 30, 5, "smart", 6, 1500,
                "super-secret-token", "", "");

        assertThat(s.credentialStatus(true).toString()).doesNotContain("super-secret-token");
    }
}
