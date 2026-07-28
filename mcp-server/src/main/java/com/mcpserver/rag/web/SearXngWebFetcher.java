package com.mcpserver.rag.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WebFetcher backed by a local SearXNG instance (open-source, native process).
 * <p>
 * Executes every contextual query variant and preserves provider rank/score as features.
 * Provider ordering is not treated as final relevance; {@link WebSearchService} deduplicates,
 * fetches page content, semantically reranks, and applies source-quality/diversity signals.
 */
@Component
public class SearXngWebFetcher implements WebFetcher {

    private static final Logger log = LoggerFactory.getLogger(SearXngWebFetcher.class);

    private final String searxngUrl;
    private final String engines;
    private final HttpClient httpClient;

    @Autowired
    public SearXngWebFetcher(
            @Value("${rag.web.searxng-url:http://127.0.0.1:8888}") String searxngUrl,
            @Value("${rag.web.engines:bing,brave,duckduckgo,startpage,stackoverflow}") String engines) {
        this.searxngUrl = searxngUrl.endsWith("/")
                ? searxngUrl.substring(0, searxngUrl.length() - 1) : searxngUrl;
        this.engines = engines == null ? "" : engines.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    SearXngWebFetcher(String searxngUrl) {
        this(searxngUrl, "bing,brave,duckduckgo,startpage,stackoverflow");
    }

    @Override
    public List<WebResult> fetch(List<String> queries, int perQuery) {
        List<WebResult> out = new ArrayList<>();
        for (String query : queries) {
            List<SearxResult> searxResults = querySearXng(query);
            int limit = Math.min(Math.max(perQuery, 0), searxResults.size());
            for (int i = 0; i < limit; i++) {
                SearxResult sr = searxResults.get(i);
                out.add(new WebResult(sr.url(), sr.title(), sr.description(), sr.engine(),
                        query, i + 1, sr.score(), sr.publishedAt()));
            }
        }
        log.info("Web fetcher: {} SearXNG candidates from {} contextual queries",
                out.size(), queries.size());
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<SearxResult> querySearXng(String query) {
        try {
            String url = searxngUrl + "/search?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&format=json&categories=general";
            if (!engines.isBlank()) {
                url += "&engines=" + URLEncoder.encode(engines, StandardCharsets.UTF_8);
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("Web fetcher: SearXNG returned HTTP {}", res.statusCode());
                return List.of();
            }
            Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(res.body(), Map.class);
            List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("results");
            if (results == null) return List.of();
            List<SearxResult> out = new ArrayList<>();
            for (Map<String, Object> r : results) {
                String rUrl = (String) r.get("url");
                String rTitle = (String) r.get("title");
                String rDesc = (String) r.get("content");
                String rEngine = engines(r);
                double rScore = r.get("score") instanceof Number n ? n.doubleValue() : 0d;
                Instant publishedAt = publishedAt(r);
                if (rUrl != null && !rUrl.isBlank()) {
                    out.add(new SearxResult(rUrl,
                            rTitle == null ? "" : rTitle,
                            rDesc == null ? "" : rDesc,
                            rEngine, rScore, publishedAt));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Web fetcher: SearXNG query failed (is SearXNG running on {}?): {}",
                    searxngUrl, e.getMessage());
            return List.of();
        }
    }

    private static String engines(Map<String, Object> result) {
        Object engines = result.get("engines");
        if (engines instanceof List<?> list) {
            return list.stream().map(String::valueOf).sorted().distinct()
                    .collect(java.util.stream.Collectors.joining(","));
        }
        return result.get("engine") == null ? "" : result.get("engine").toString();
    }

    private static Instant publishedAt(Map<String, Object> result) {
        Object value = result.get("publishedDate");
        if (value == null) value = result.get("published_date");
        if (value == null) return null;
        try {
            return Instant.parse(value.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private record SearxResult(String url, String title, String description, String engine,
                               double score, Instant publishedAt) {}
}
