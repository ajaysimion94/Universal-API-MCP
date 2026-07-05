package com.mcpserver.rag.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WebFetcher backed by a local SearXNG instance (open-source, native process).
 * <p>
 * Flow: query SearXNG's JSON API → take the title + description snippet SearXNG already
 * provides per result → return {@link WebResult}s. No page fetching or Tika extraction —
 * SearXNG's snippet is the description, and the user clicks through to the URL for full
 * content. Failures degrade to an empty list if SearXNG is down.
 */
@Component
public class SearXngWebFetcher implements WebFetcher {

    private static final Logger log = LoggerFactory.getLogger(SearXngWebFetcher.class);

    private final String searxngUrl;
    private final HttpClient httpClient;

    public SearXngWebFetcher(
            @Value("${rag.web.searxng-url:http://127.0.0.1:8888}") String searxngUrl) {
        this.searxngUrl = searxngUrl.endsWith("/")
                ? searxngUrl.substring(0, searxngUrl.length() - 1) : searxngUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public List<WebResult> fetch(String query, int topN) {
        List<SearxResult> searxResults = querySearXng(query);
        if (searxResults.isEmpty()) {
            log.info("Web fetcher: SearXNG returned no results for '{}'", query);
            return List.of();
        }

        List<WebResult> out = new ArrayList<>();
        for (SearxResult sr : searxResults) {
            if (out.size() >= topN) break;
            out.add(new WebResult(sr.url(), sr.title(), sr.description(), sr.engine()));
        }
        log.info("Web fetcher: {} SearXNG results for '{}'", out.size(), query);
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<SearxResult> querySearXng(String query) {
        try {
            String url = searxngUrl + "/search?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&format=json&categories=general";
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
                String rEngine = r.get("engine") == null ? "" : r.get("engine").toString();
                if (rUrl != null && !rUrl.isBlank()) {
                    out.add(new SearxResult(rUrl,
                            rTitle == null ? "" : rTitle,
                            rDesc == null ? "" : rDesc,
                            rEngine));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Web fetcher: SearXNG query failed (is SearXNG running on {}?): {}",
                    searxngUrl, e.getMessage());
            return List.of();
        }
    }

    private record SearxResult(String url, String title, String description, String engine) {}
}
