package com.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes one imported tool against its connection's API: validates args against the generated
 * schema (never executes on violation — §8 self-correction), renders URL/query/headers/body,
 * injects connection-level auth, and applies the sandbox-lite guardrails from §9 — the target is
 * structurally pinned to the connection's own host, invocations are rate-limited per tool, and
 * responses are size-capped.
 */
@Component
public class ApiToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ApiToolExecutor.class);

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_DISPLAY_CHARS = 20_000;

    private final CredentialCipher credentialCipher;
    private final int rateLimitPerMinute;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** toolId → window start millis + count; a coarse fixed-window limiter is enough here. */
    private final Map<String, long[]> rateWindows = new ConcurrentHashMap<>();

    public ApiToolExecutor(CredentialCipher credentialCipher,
                           @Value("${tools.rate-limit-per-minute:10}") int rateLimitPerMinute) {
        this.credentialCipher = credentialCipher;
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public ToolInvocationResult execute(ApiTool tool, Connection connection, Map<String, Object> args)
            throws Exception {
        if (!tool.enabled()) {
            throw new IllegalStateException(tool.pending()
                    ? "Tool " + tool.name() + " is pending approval — enable it on the Connections page"
                    : "Tool " + tool.name() + " is disabled");
        }
        checkRateLimit(tool);

        JsonNode schema = mapper.readTree(tool.paramsSchema());
        Map<String, Object> merged = mergeDefaults(schema, args);
        List<SchemaValidator.Violation> violations = SchemaValidator.validate(schema, merged);
        if (!violations.isEmpty()) {
            throw new ToolValidationException(violations);
        }

        Map<String, String> locations = readStringMap(tool.paramLocations());
        URI target = renderUri(tool, connection, merged, locations);
        assertSameHost(target, connection);

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(target)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json, */*;q=0.5");
        readStringMap(tool.headers()).forEach(request::header);
        for (var e : merged.entrySet()) {
            if ("header".equals(locations.get(e.getKey())) && e.getValue() != null) {
                request.header(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        applyAuth(request, connection);

        String body = renderBody(tool, merged, locations);
        if (body != null && !tool.isRead()) {
            request.method(tool.httpMethod(), HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(tool.httpMethod(), HttpRequest.BodyPublishers.noBody());
        }

        long start = System.currentTimeMillis();
        HttpResponse<byte[]> response = httpClient.send(request.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        long latency = System.currentTimeMillis() - start;

        String summary = tool.httpMethod() + " " + target;
        log.info("Tool {} → {} in {}ms", tool.name(), response.statusCode(), latency);
        return toResult(response, latency, summary);
    }

    // --- rendering ---------------------------------------------------------

    private URI renderUri(ApiTool tool, Connection connection, Map<String, Object> args,
                          Map<String, String> locations) {
        String path = tool.urlTemplate();
        for (var e : args.entrySet()) {
            if ("path".equals(locations.get(e.getKey())) && e.getValue() != null) {
                path = path.replace("{" + e.getKey() + "}",
                        URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
            }
        }

        StringBuilder query = new StringBuilder();
        for (var e : args.entrySet()) {
            if ("query".equals(locations.get(e.getKey())) && e.getValue() != null
                    && !String.valueOf(e.getValue()).isBlank()) {
                query.append(query.isEmpty() ? "?" : "&")
                        .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
            }
        }

        String base = connection.baseUrl().replaceAll("/+$", "");
        return URI.create(base + path + query);
    }

    /**
     * Body args merge into the spec's example body (so fields the caller didn't mention keep
     * their skeleton values only when required by the endpoint — we send just the caller's args
     * plus template values for fields present in the template).
     */
    private String renderBody(ApiTool tool, Map<String, Object> args, Map<String, String> locations)
            throws Exception {
        boolean hasBodyArg = args.keySet().stream().anyMatch(k -> "body".equals(locations.get(k)));
        if (!hasBodyArg && tool.bodyTemplate() == null) return null;

        ObjectNode body = tool.bodyTemplate() != null && !tool.bodyTemplate().isBlank()
                ? (ObjectNode) mapper.readTree(tool.bodyTemplate())
                : mapper.createObjectNode();
        for (var e : args.entrySet()) {
            if (!"body".equals(locations.get(e.getKey())) || e.getValue() == null) continue;
            body.set(e.getKey(), mapper.valueToTree(coerce(e.getValue())));
        }
        return body.toString();
    }

    /** Free-text args arrive as strings; send "123" as 123 and "true" as true where unambiguous. */
    private static Object coerce(Object value) {
        if (!(value instanceof String s)) return value;
        if (s.matches("-?\\d+")) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
            }
        }
        if (s.matches("-?\\d+\\.\\d+")) return Double.parseDouble(s);
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) return Boolean.parseBoolean(s);
        return s;
    }

    private Map<String, String> readStringMap(String json) throws Exception {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.isBlank()) return map;
        mapper.readTree(json).properties().forEach(e -> map.put(e.getKey(), e.getValue().asText()));
        return map;
    }

    private Map<String, Object> mergeDefaults(JsonNode schema, Map<String, Object> args) {
        Map<String, Object> merged = new HashMap<>(args);
        schema.path("properties").properties().forEach(e -> {
            JsonNode dflt = e.getValue().path("default");
            if (!dflt.isMissingNode() && !merged.containsKey(e.getKey())) {
                merged.put(e.getKey(), dflt.isTextual() ? dflt.asText() : mapper.convertValue(dflt, Object.class));
            }
        });
        return merged;
    }

    // --- guardrails ---------------------------------------------------------

    /** Structural given renderUri always joins baseUrl — asserted anyway per §9. */
    private static void assertSameHost(URI target, Connection connection) {
        String allowedHost = URI.create(connection.baseUrl()).getHost();
        if (allowedHost == null || !allowedHost.equalsIgnoreCase(target.getHost())) {
            throw new IllegalStateException("Tool target host " + target.getHost()
                    + " is outside the connection's allowlisted host " + allowedHost);
        }
    }

    private void checkRateLimit(ApiTool tool) {
        long now = System.currentTimeMillis();
        long[] window = rateWindows.compute(tool.id(), (id, w) -> {
            if (w == null || now - w[0] >= 60_000) return new long[]{now, 1};
            w[1]++;
            return w;
        });
        if (window[1] > rateLimitPerMinute) {
            throw new IllegalStateException("Rate limit exceeded for tool " + tool.name()
                    + " (" + rateLimitPerMinute + "/min)");
        }
    }

    // --- auth + response ----------------------------------------------------

    private void applyAuth(HttpRequest.Builder request, Connection connection) {
        String secret = connection.authSecretEncrypted() == null ? null
                : credentialCipher.decrypt(connection.authSecretEncrypted());
        switch (connection.authMode()) {
            case BASIC -> {
                if (secret != null) {
                    String token = Base64.getEncoder().encodeToString(
                            (connection.authUsername() + ":" + secret).getBytes(StandardCharsets.UTF_8));
                    request.header("Authorization", "Basic " + token);
                }
            }
            case BEARER -> {
                if (secret != null) request.header("Authorization", "Bearer " + secret);
            }
            case API_KEY_HEADER -> {
                if (secret != null && connection.authUsername() != null) {
                    request.header(connection.authUsername(), secret);
                }
            }
            case NONE, OAUTH2 -> {
                // NONE sends nothing; OAUTH2 is a reserved extension point (unimplemented)
            }
        }
    }

    private ToolInvocationResult toResult(HttpResponse<byte[]> response, long latency, String summary) {
        byte[] raw = response.body();
        boolean truncated = raw.length > MAX_RESPONSE_BYTES;
        String text = new String(raw, 0, Math.min(raw.length, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
        String contentType = response.headers().firstValue("Content-Type").orElse("");

        if (!truncated && (contentType.contains("json") || text.trim().startsWith("{") || text.trim().startsWith("["))) {
            try {
                text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(text));
            } catch (Exception ignored) {
                // leave as-is when the body isn't valid JSON after all
            }
        }
        if (text.length() > MAX_DISPLAY_CHARS) {
            text = text.substring(0, MAX_DISPLAY_CHARS);
            truncated = true;
        }
        return new ToolInvocationResult(response.statusCode(), latency, contentType, text, truncated, summary);
    }

    /**
     * Validates and renders the request a write tool <em>would</em> send — method, resolved URL,
     * body — without sending it. Backs the preview→approve step (§5.8/§7.2) for state-changing
     * tools invoked from search. Auth headers are never part of the preview.
     */
    public Map<String, Object> renderPreview(ApiTool tool, Connection connection, Map<String, Object> args)
            throws Exception {
        JsonNode schema = mapper.readTree(tool.paramsSchema());
        Map<String, Object> merged = mergeDefaults(schema, args);
        List<SchemaValidator.Violation> violations = SchemaValidator.validate(schema, merged);
        if (!violations.isEmpty()) {
            throw new ToolValidationException(violations);
        }
        Map<String, String> locations = readStringMap(tool.paramLocations());
        Map<String, Object> preview = new HashMap<>();
        preview.put("method", tool.httpMethod());
        preview.put("url", renderUri(tool, connection, merged, locations).toString());
        String body = renderBody(tool, merged, locations);
        if (body != null) preview.put("body", body);
        return preview;
    }

    /** Raw response bytes for knowledge-source ingestion (no pretty-print/display cap). */
    public byte[] executeForIngestion(ApiTool tool, Connection connection) throws Exception {
        ToolInvocationResult result = execute(tool, connection, Map.of());
        if (result.status() >= 400) {
            throw new IllegalStateException("Tool " + tool.name() + " returned HTTP " + result.status());
        }
        return result.body().getBytes(StandardCharsets.UTF_8);
    }
}
