package com.mcpserver.rag.web;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class SearXngWebFetcherTests {

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
    void executesEveryContextualQueryAndPreservesProviderSignals() {
        server.stubFor(get(urlPathEqualTo("/search"))
                .willReturn(okJson("""
                        {"results":[{
                          "url":"https://docs.example.test/reference",
                          "title":"Official reference",
                          "content":"Primary documentation",
                          "engines":["bing","duckduckgo"],
                          "score":2.5,
                          "publishedDate":"2026-07-01T00:00:00Z"
                        }]}
                        """)));
        SearXngWebFetcher fetcher = new SearXngWebFetcher(server.baseUrl());

        var results = fetcher.fetch(List.of("first intent", "second intent"), 3);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(WebFetcher.WebResult::query)
                .containsExactly("first intent", "second intent");
        assertThat(results.get(0).engine()).isEqualTo("bing,duckduckgo");
        assertThat(results.get(0).providerScore()).isEqualTo(2.5);
        assertThat(results.get(0).publishedAt()).isNotNull();
        server.verify(2, getRequestedFor(urlPathEqualTo("/search"))
                .withQueryParam("engines",
                        equalTo("bing,brave,duckduckgo,startpage,stackoverflow")));
    }
}
