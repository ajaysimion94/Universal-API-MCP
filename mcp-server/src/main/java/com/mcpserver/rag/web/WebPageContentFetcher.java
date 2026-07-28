package com.mcpserver.rag.web;

import jakarta.annotation.PreDestroy;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bounded, SSRF-safe page retrieval for the strongest web candidates.
 */
@Component
public class WebPageContentFetcher {

    private static final Logger log = LoggerFactory.getLogger(WebPageContentFetcher.class);
    private static final int MAX_REDIRECTS = 3;

    private final HttpClient client;
    private final ExecutorService executor;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final int maxContentChars;
    private final boolean allowPrivateNetwork;

    public WebPageContentFetcher(
            @Value("${rag.web.page-fetch-timeout-seconds:8}") int timeoutSeconds,
            @Value("${rag.web.max-response-bytes:2097152}") int maxResponseBytes,
            @Value("${rag.web.max-content-chars:20000}") int maxContentChars,
            @Value("${rag.web.allow-private-network:false}") boolean allowPrivateNetwork) {
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.maxResponseBytes = Math.max(16_384, maxResponseBytes);
        this.maxContentChars = Math.max(1_000, maxContentChars);
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "web-page-fetch");
            thread.setDaemon(true);
            return thread;
        });
        this.client = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(executor)
                .build();
    }

    public Map<String, String> fetch(List<WebFetcher.WebResult> candidates, int limit) {
        List<WebFetcher.WebResult> selected = candidates.stream()
                .limit(Math.max(0, limit))
                .toList();
        List<CompletableFuture<Map.Entry<String, String>>> futures = selected.stream()
                .map(result -> CompletableFuture.supplyAsync(() ->
                        Map.entry(result.url(), fetchOne(result.url())), executor))
                .toList();

        Map<String, String> content = new LinkedHashMap<>();
        for (CompletableFuture<Map.Entry<String, String>> future : futures) {
            try {
                Map.Entry<String, String> entry = future.join();
                if (!entry.getValue().isBlank()) content.put(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                log.debug("Web page fetch failed: {}", e.getMessage());
            }
        }
        return content;
    }

    private String fetchOne(String url) {
        try {
            URI current = URI.create(url);
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                validatePublicHttpUrl(current);
                HttpRequest request = HttpRequest.newBuilder(current)
                        .header("Accept", "text/html, text/plain, application/pdf, application/xhtml+xml;q=0.9, */*;q=0.1")
                        .header("User-Agent", "MCPServerWebResearch/1.0")
                        .timeout(timeout)
                        .GET()
                        .build();
                HttpResponse<InputStream> response = client.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                if (isRedirect(response.statusCode())) {
                    response.body().close();
                    String location = response.headers().firstValue("location").orElse("");
                    if (location.isBlank() || redirects == MAX_REDIRECTS) return "";
                    current = current.resolve(location);
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    response.body().close();
                    return "";
                }
                byte[] bytes;
                try (InputStream body = response.body()) {
                    bytes = body.readNBytes(maxResponseBytes + 1);
                }
                if (bytes.length == 0 || bytes.length > maxResponseBytes) return "";
                String contentType = response.headers().firstValue("content-type").orElse("");
                if (isUnsupported(contentType)) return "";
                Tika tika = new Tika();
                tika.setMaxStringLength(maxContentChars);
                String extracted = tika.parseToString(new ByteArrayInputStream(bytes))
                        .replaceAll("\\s+", " ").trim();
                return extracted.length() > maxContentChars
                        ? extracted.substring(0, maxContentChars).trim()
                        : extracted;
            }
        } catch (Exception e) {
            log.debug("Could not extract {}: {}", url, e.getMessage());
        }
        return "";
    }

    private void validatePublicHttpUrl(URI uri) throws Exception {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Only HTTP(S) web pages are supported");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Invalid web result URL");
        }
        if (allowPrivateNetwork) return;
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException("Private network web result blocked");
            }
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isUnsupported(String contentType) {
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("image/") || type.startsWith("audio/")
                || type.startsWith("video/") || type.contains("zip")
                || type.contains("octet-stream");
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
