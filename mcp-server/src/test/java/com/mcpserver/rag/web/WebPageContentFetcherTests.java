package com.mcpserver.rag.web;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class WebPageContentFetcherTests {

    private WireMockServer server;
    private WebPageContentFetcher fetcher;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        fetcher = new WebPageContentFetcher(2, 100_000, 5_000, true);
    }

    @AfterEach
    void stop() {
        fetcher.close();
        server.stop();
    }

    @Test
    void followsValidatedRedirectAndExtractsTextInsteadOfHtmlMarkup() {
        server.stubFor(get("/start").willReturn(temporaryRedirect("/article")));
        server.stubFor(get("/article").willReturn(ok()
                .withHeader("Content-Type", "text/html")
                .withBody("<html><body><h1>Migration guide</h1><p>Use Java 17.</p></body></html>")));
        WebFetcher.WebResult result = new WebFetcher.WebResult(
                server.baseUrl() + "/start", "Guide", "snippet", "test",
                "migration", 1, 1d, null);

        assertThat(fetcher.fetch(List.of(result), 1).get(result.url()))
                .contains("Migration guide", "Use Java 17")
                .doesNotContain("<h1>");
    }

    @Test
    void rejectsOversizedBodies() {
        server.stubFor(get("/large").willReturn(ok("x".repeat(120_000))));
        WebFetcher.WebResult result = new WebFetcher.WebResult(
                server.baseUrl() + "/large", "Large", "", "test",
                "large", 1, 1d, null);

        assertThat(fetcher.fetch(List.of(result), 1)).isEmpty();
    }
}
