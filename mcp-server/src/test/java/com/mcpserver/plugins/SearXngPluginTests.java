package com.mcpserver.plugins;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearXngPluginTests {

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void reportsReadyFromHttpHealthEvenWithoutInMemoryChildProcess() {
        server.stubFor(get("/").willReturn(ok("searxng")));
        PluginStateStore state = mock(PluginStateStore.class);
        when(state.isEnabled("searxng")).thenReturn(true);
        when(state.isInstalled("searxng")).thenReturn(true);

        SearXngPlugin plugin = new SearXngPlugin(state, server.baseUrl());

        assertThat(plugin.isRunning()).isTrue();
        assertThat(plugin.isReady()).isTrue();
        assertThat(plugin.status()).isEqualTo(Plugin.Status.ACTIVE);
        assertThat(plugin.health()).contains("external process");
    }

    @Test
    void reportsInstalledButNotReadyWhenEndpointIsDown() {
        PluginStateStore state = mock(PluginStateStore.class);
        when(state.isEnabled("searxng")).thenReturn(true);
        when(state.isInstalled("searxng")).thenReturn(true);
        String baseUrl = server.baseUrl();
        server.stop();

        SearXngPlugin plugin = new SearXngPlugin(state, baseUrl);

        assertThat(plugin.isReady()).isFalse();
        assertThat(plugin.status()).isEqualTo(Plugin.Status.INSTALLED);
    }
}
