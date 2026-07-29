package com.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.mcpserver.config.TlsHttpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches an API spec from a URL, resolving Swagger UI / ReDoc / RapiDoc pages down to the
 * underlying spec document. This is the flakiest part of spec import (every Swagger UI deployment
 * differs) — file upload is the reliable fallback and always offered in the UI.
 */
@Component
public class SpecFetcher {

    private static final Logger log = LoggerFactory.getLogger(SpecFetcher.class);

    private static final int MAX_CANDIDATE_FETCHES = 8;
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final List<String> WELL_KNOWN_PATHS = List.of(
            "/v3/api-docs", "/v3/api-docs.yaml", "/openapi.json", "/openapi.yaml",
            "/swagger.json", "/api-docs", "/swagger/v1/swagger.json");

    /** Spec-URL hints inside a Swagger UI / ReDoc / RapiDoc HTML page. */
    private static final List<Pattern> HTML_SPEC_PATTERNS = List.of(
            Pattern.compile("[\"']?(?:url|configUrl)[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']"),
            Pattern.compile("spec-url\\s*=\\s*[\"']([^\"']+)[\"']"),
            Pattern.compile("data-url\\s*=\\s*[\"']([^\"']+)[\"']"));

    private final HttpClient httpClient;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new YAMLMapper();
    private final List<SpecParser> parsers;

    @Autowired
    public SpecFetcher(List<SpecParser> parsers, TlsHttpClientFactory tlsHttpClientFactory) {
        this.parsers = parsers;
        this.httpClient = tlsHttpClientFactory.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Pure parser tests do not need application TLS configuration. */
    SpecFetcher(List<SpecParser> parsers) {
        this.parsers = parsers;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record FetchedSpec(String content, JsonNode parsed, SpecParser parser, String resolvedUrl) {}

    public FetchedSpec fetch(String url) throws Exception {
        List<String> tried = new ArrayList<>();
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(url);

        String pageBody = null;
        int fetches = 0;
        for (String candidate : new ArrayList<>(candidates)) {
            fetches++;
            tried.add(candidate);
            try {
                String body = get(candidate);
                FetchedSpec spec = tryParse(body, candidate);
                if (spec != null) return spec;
                pageBody = body;
            } catch (Exception e) {
                log.debug("Spec candidate {} failed on initial fetch: {}", candidate, e.getMessage());
            }
        }

        // The URL served something that isn't a spec — scan it for spec-URL hints (Swagger UI page)
        if (pageBody != null && looksLikeHtml(pageBody)) {
            for (Pattern pattern : HTML_SPEC_PATTERNS) {
                Matcher m = pattern.matcher(pageBody);
                while (m.find()) {
                    String href = m.group(1);
                    if (href.startsWith("data:") || href.startsWith("#")) continue;
                    candidates.add(URI.create(url).resolve(href).toString());
                }
            }
        }

        // Well-known spec locations on both the page's directory and the origin root
        URI base = URI.create(url);
        String origin = base.getScheme() + "://" + base.getRawAuthority();
        for (String path : WELL_KNOWN_PATHS) {
            candidates.add(origin + path);
        }

        for (String candidate : candidates) {
            if (tried.contains(candidate)) continue;
            if (fetches >= MAX_CANDIDATE_FETCHES) break;
            fetches++;
            tried.add(candidate);
            try {
                String body = get(candidate);
                FetchedSpec spec = tryParse(body, candidate);
                if (spec != null) return spec;
                // a swagger-config document points at the real spec — follow one level
                JsonNode config = parseTree(body);
                String next = configSpecUrl(config, candidate);
                if (next != null && !tried.contains(next) && fetches < MAX_CANDIDATE_FETCHES) {
                    fetches++;
                    tried.add(next);
                    FetchedSpec fromConfig = tryParse(get(next), next);
                    if (fromConfig != null) return fromConfig;
                }
            } catch (Exception e) {
                log.debug("Spec candidate {} failed: {}", candidate, e.getMessage());
            }
        }

        throw new IllegalArgumentException(
                "No Postman collection or OpenAPI spec found. Tried: " + String.join(", ", tried)
                        + ". If the spec isn't publicly reachable, upload it as a file instead.");
    }

    /** Parses raw spec text (uploaded file or stored spec_document) — no network involved. */
    public FetchedSpec parseContent(String content, String sourceUrl) {
        FetchedSpec spec = tryParse(content, sourceUrl);
        if (spec == null) {
            throw new IllegalArgumentException(
                    "Document is not a recognizable Postman collection, Swagger 2.0 document, "
                            + "or OpenAPI 3.x spec.");
        }
        return spec;
    }

    private FetchedSpec tryParse(String body, String url) {
        JsonNode root = parseTree(body);
        if (root == null) return null;
        for (SpecParser parser : parsers) {
            if (parser.supports(root)) return new FetchedSpec(body, root, parser, url);
        }
        return null;
    }

    private JsonNode parseTree(String body) {
        if (body == null || body.isBlank()) return null;
        String trimmed = body.trim();
        try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return jsonMapper.readTree(trimmed);
            }
            if (looksLikeHtml(trimmed)) return null;
            return yamlMapper.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    private static String configSpecUrl(JsonNode config, String configUrl) {
        if (config == null) return null;
        String direct = config.path("url").asText("");
        if (!direct.isBlank()) return URI.create(configUrl).resolve(direct).toString();
        JsonNode urls = config.path("urls");
        if (urls.isArray() && !urls.isEmpty()) {
            String first = urls.get(0).path("url").asText("");
            if (!first.isBlank()) return URI.create(configUrl).resolve(first).toString();
        }
        return null;
    }

    private static boolean looksLikeHtml(String body) {
        String head = body.substring(0, Math.min(body.length(), 512)).toLowerCase();
        return head.contains("<!doctype html") || head.contains("<html");
    }

    private String get(String url) throws Exception {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Only http/https spec URLs are supported: " + url);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept", "application/json, application/yaml, text/html;q=0.5, */*;q=0.1")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new IllegalArgumentException("HTTP " + response.statusCode() + " from " + url);
        }
        byte[] body = response.body();
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Response from " + url + " exceeds 10MB");
        }
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }
}
