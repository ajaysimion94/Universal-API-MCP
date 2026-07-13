package com.mcpserver.connectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AtlassianAuthTests {

    @Test
    void encodesUsernameAndPasswordAsBasicAuth() {
        String header = AtlassianAuth.basicAuthHeader("user@example.com", "s3cr3t");

        assertThat(header).startsWith("Basic ");
        String decoded = new String(java.util.Base64.getDecoder()
                .decode(header.substring("Basic ".length())));
        assertThat(decoded).isEqualTo("user@example.com:s3cr3t");
    }
}
