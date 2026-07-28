package com.mcpserver.rag.web;

import com.mcpserver.models.Chunk;
import com.mcpserver.rag.embedding.EmbeddingClient;
import com.mcpserver.rag.reranker.Reranker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSearchServiceTests {

    @Test
    void deduplicatesTrackingUrlsAndRanksPrimarySourcesBeyondProviderOrder() {
        WebFetcher fetcher = (queries, perQuery) -> {
            List<WebFetcher.WebResult> results = new ArrayList<>();
            results.add(result("https://blog.test/ultimate-guide", "Ultimate complete guide",
                    queries.get(0), 1, 4d));
            results.add(result("https://docs.spring.io/spring-boot/reference/?utm_source=search",
                    "Spring Boot Reference Documentation", queries.get(0), 3, 1d));
            results.add(result("https://docs.spring.io/spring-boot/reference",
                    "Official Spring Boot Reference Documentation", queries.get(1), 1, 3d));
            results.add(result("https://github.com/spring-projects/spring-boot/releases",
                    "Spring Boot releases", queries.get(2), 1, 2d));
            return results;
        };
        WebPageContentFetcher pageFetcher = mock(WebPageContentFetcher.class);
        when(pageFetcher.fetch(anyList(), anyInt())).thenReturn(Map.of(
                "https://docs.spring.io/spring-boot/reference/?utm_source=search",
                "System requirements, upgrading instructions, and supported Java versions."));
        Reranker reranker = (query, candidates) -> candidates.stream()
                .map(chunk -> new Reranker.ScoredChunk(chunk,
                        chunk.url().contains("blog.test") ? 0.90f : 0.75f))
                .toList();
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        WebSearchService service = new WebSearchService(
                new WebQueryPlanner(4, java.time.Clock.systemUTC()),
                fetcher, pageFetcher, reranker, embeddings, 20, 5, 0.20);

        var ranked = service.search(
                "Spring Boot upgrade compatibility official documentation",
                List.of("Java 17 deployment"), 3);

        assertThat(ranked).hasSize(3);
        assertThat(ranked).extracting(item -> item.result().url())
                .containsOnlyOnce("https://docs.spring.io/spring-boot/reference/?utm_source=search");
        assertThat(ranked.get(0).result().url()).contains("docs.spring.io");
        assertThat(ranked).extracting(item -> host(item.result().url()))
                .doesNotHaveDuplicates();
    }

    @Test
    void canonicalizationDropsTrackingAndFragmentsButKeepsMeaningfulParameters() {
        assertThat(WebSearchService.canonicalUrl(
                "https://Example.com/docs/?utm_source=x&version=3#section"))
                .isEqualTo("https://example.com/docs?version=3");
    }

    @Test
    void officialDocumentationScoresAboveSeoStyleTitle() {
        var official = result("https://docs.example.com/reference",
                "Official API Reference Documentation", "q", 3, 0d);
        var seo = result("https://random.test/post",
                "Ultimate Complete Best Guide", "q", 1, 0d);

        assertThat(WebSearchService.authorityScore("example API reference", official))
                .isGreaterThan(WebSearchService.authorityScore("example API reference", seo));
    }

    private static WebFetcher.WebResult result(String url, String title, String query,
                                                int rank, double score) {
        return new WebFetcher.WebResult(url, title, "Spring Boot migration and compatibility",
                "test", query, rank, score, Instant.parse("2026-07-01T00:00:00Z"));
    }

    private static String host(String url) {
        return java.net.URI.create(url).getHost();
    }
}
