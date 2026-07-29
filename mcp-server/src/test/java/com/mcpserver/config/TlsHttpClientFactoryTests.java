package com.mcpserver.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TlsHttpClientFactoryTests {

    @Test
    void buildsClientWithNormalJvmTrust() {
        TlsHttpClientFactory factory = new TlsHttpClientFactory("", false, false);

        assertThat(factory.builder().build().sslContext()).isNotNull();
    }

    @Test
    void buildsClientWhenCertificateValidationIsExplicitlyDisabled() {
        TlsHttpClientFactory factory = new TlsHttpClientFactory("", false, true);

        assertThat(factory.builder().build().sslContext()).isNotNull();
    }

    @Test
    void missingConfiguredCertificateFailsClosed() {
        assertThatThrownBy(() ->
                new TlsHttpClientFactory("does-not-exist/corporate-root.pem", false, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TLS CA certificate file does not exist");
    }
}
