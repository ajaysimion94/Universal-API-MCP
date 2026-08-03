package com.mcpserver.connectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtlassianAuthTests {

    @Test
    void encodesUsernameAndPasswordAsBasicAuth() {
        String header = AtlassianAuth.basicAuthHeader("user@example.com", "s3cr3t");

        assertThat(header).startsWith("Basic ");
        String decoded = new String(java.util.Base64.getDecoder()
                .decode(header.substring("Basic ".length())));
        assertThat(decoded).isEqualTo("user@example.com:s3cr3t");
    }

    @Test
    void sendsDataCenterPersonalAccessTokenAsBearer() {
        assertThat(AtlassianAuth.authorizationHeader(AuthMode.BEARER, null, "pat-secret"))
                .isEqualTo("Bearer pat-secret");
    }

    @Test
    void rejectsUnsupportedAtlassianAuthMode() {
        assertThatThrownBy(() -> AtlassianAuth.authorizationHeader(AuthMode.NONE, null, "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BASIC or BEARER");
    }
}
