package com.mcpserver.copilot;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotClientTests {

    private static String fakeJwt(String sub) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }

    @Test
    void derivesGoogleIdentityTypeFromSubClaim() {
        assertThat(CopilotClient.deriveIdentityType(fakeJwt("google-oauth2|123456"))).isEqualTo("google");
    }

    @Test
    void derivesAppleIdentityTypeFromSubClaim() {
        assertThat(CopilotClient.deriveIdentityType(fakeJwt("apple|123456"))).isEqualTo("apple");
    }

    @Test
    void returnsNullForOtherProvidersAndGarbage() {
        assertThat(CopilotClient.deriveIdentityType(fakeJwt("waad|123456"))).isNull();
        assertThat(CopilotClient.deriveIdentityType("not-a-jwt")).isNull();
        assertThat(CopilotClient.deriveIdentityType(null)).isNull();
        assertThat(CopilotClient.deriveIdentityType("")).isNull();
    }

    @Test
    void explicitIdentityTypeWinsOverDerivation() {
        CopilotClient client = new CopilotClient();
        client.setAccessToken(fakeJwt("google-oauth2|123456"));
        assertThat(client.identityType()).isEqualTo("google");
        client.setIdentityType("msa");
        assertThat(client.identityType()).isEqualTo("msa");
    }

    @Test
    void clearsCredentialsBackToAnonymous() {
        CopilotClient client = new CopilotClient();
        client.setAccessToken(fakeJwt("google-oauth2|123456"));
        client.setIdentityType("google");
        client.setSessionCookies(java.util.Map.of("MUID", "abc"));
        assertThat(client.hasCredentials()).isTrue();
        client.clearCredentials();
        assertThat(client.hasCredentials()).isFalse();
        assertThat(client.identityType()).isNull();
    }
}
