package com.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

    /**
     * Per-invocation request customization layered on top of a tool's spec-derived shape —
     * the Postman-style "override anything before sending" seam. Never persisted with a
     * secret in it for write tools; see {@link #executeRaw} guard on {@code auth}.
     */
    public record InvokeOverrides(
            Map<String, String> extraHeaders,
            Map<String, String> extraQueryParams,
            String bodyMode,
            String rawBody,
            String rawContentType,
            AuthOverride auth
    ) {
        private static final InvokeOverrides EMPTY = new InvokeOverrides(Map.of(), Map.of(), null, null, null, null);

        public static InvokeOverrides empty() {
            return EMPTY;
        }

        public InvokeOverrides {
            if (extraHeaders == null) extraHeaders = Map.of();
            if (extraQueryParams == null) extraQueryParams = Map.of();
        }
    }

    /** Same four modes the connection itself supports (see DECISIONS.md) — never OAuth2/Digest/etc. */
    public record AuthOverride(String mode, String username, String secret, String headerName) {
    }

    private record RawResult(HttpResponse<byte[]> response, long latencyMs, URI target) {}

    private RawResult executeRaw(ApiTool tool, Connection connection, Map<String, Object> args,
                                 InvokeOverrides overrides) throws Exception {
        if (!tool.enabled()) {
            throw new IllegalStateException(tool.pending()
                    ? "Tool " + tool.name() + " is pending approval — enable it on the Connections page"
                    : "Tool " + tool.name() + " is disabled");
        }
        if (overrides.auth() != null && !tool.isRead()) {
            throw new IllegalArgumentException(
                    "Auth override is only available for read (GET) tools — write tools always use "
                            + "the connection's stored credentials");
        }
        checkRateLimit(tool);

        JsonNode schema = mapper.readTree(tool.paramsSchema());
        Map<String, String> locations = readStringMap(tool.paramLocations());
        Map<String, Object> merged = mergeDefaults(schema, args);
        List<SchemaValidator.Violation> violations =
                SchemaValidator.validate(effectiveSchemaForValidation(schema, locations, overrides), merged);
        if (!violations.isEmpty()) {
            throw new ToolValidationException(violations);
        }

        URI target = renderUri(tool, connection, merged, locations, overrides.extraQueryParams());
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
        overrides.extraHeaders().forEach(request::setHeader);
        if (overrides.auth() != null) {
            applyAuthOverride(request, overrides.auth());
        } else {
            applyAuth(request, connection, tool);
        }

        String body = renderBody(tool, merged, locations, overrides);
        if ("RAW".equals(overrides.bodyMode()) && overrides.rawContentType() != null
                && !overrides.rawContentType().isBlank()) {
            request.setHeader("Content-Type", overrides.rawContentType());
        }
        if (body != null) {
            request.method(tool.httpMethod(), HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(tool.httpMethod(), HttpRequest.BodyPublishers.noBody());
        }

        long start = System.currentTimeMillis();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ToolExecutionException("Request to " + target + " timed out — the service "
                    + "didn't respond in time", e);
        } catch (java.net.UnknownHostException e) {
            throw new ToolExecutionException("Could not resolve host \"" + target.getHost()
                    + "\" — check the connection's base URL", e);
        } catch (java.net.ConnectException e) {
            throw new ToolExecutionException("Could not connect to " + originOf(target)
                    + " — is the service running?", e);
        } catch (java.io.IOException e) {
            throw new ToolExecutionException("Network error calling " + target + ": "
                    + e.getMessage(), e);
        }
        long latency = System.currentTimeMillis() - start;

        log.info("Tool {} → {} in {}ms", tool.name(), response.statusCode(), latency);
        return new RawResult(response, latency, target);
    }

    public ToolInvocationResult execute(ApiTool tool, Connection connection, Map<String, Object> args)
            throws Exception {
        return execute(tool, connection, args, InvokeOverrides.empty());
    }

    public ToolInvocationResult execute(ApiTool tool, Connection connection, Map<String, Object> args,
                                        InvokeOverrides overrides) throws Exception {
        RawResult raw = executeRaw(tool, connection, args, overrides);
        String summary = tool.httpMethod() + " " + raw.target();
        return toResult(raw.response(), raw.latencyMs(), summary);
    }

    // --- rendering ---------------------------------------------------------

    private URI renderUri(ApiTool tool, Connection connection, Map<String, Object> args,
                          Map<String, String> locations, Map<String, String> extraQueryParams) {
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
        for (var e : extraQueryParams.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            query.append(query.isEmpty() ? "?" : "&")
                    .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }

        String base = connection.baseUrl().replaceAll("/+$", "");
        return URI.create(base + path + query);
    }

    /**
     * Body args merge into the spec's example body (so fields the caller didn't mention keep
     * their skeleton values only when required by the endpoint — we send just the caller's args
     * plus template values for fields present in the template). A "RAW" override bypasses this
     * entirely and sends the caller's text verbatim; "NONE" forces no body.
     */
    private String renderBody(ApiTool tool, Map<String, Object> args, Map<String, String> locations,
                              InvokeOverrides overrides) throws Exception {
        if ("NONE".equals(overrides.bodyMode())) return null;
        if ("RAW".equals(overrides.bodyMode())) return overrides.rawBody();

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

    /**
     * A "RAW"/"NONE" body override bypasses the schema-driven body entirely, so required
     * properties located in the body would otherwise block a legitimate override with a
     * spurious "missing parameter" violation — strip them from the validated {@code required}
     * set in that case. Path/query/header requirements are untouched.
     */
    private JsonNode effectiveSchemaForValidation(JsonNode schema, Map<String, String> locations,
                                                   InvokeOverrides overrides) {
        if (!"RAW".equals(overrides.bodyMode()) && !"NONE".equals(overrides.bodyMode())) return schema;
        JsonNode requiredNode = schema.path("required");
        if (!requiredNode.isArray()) return schema;
        boolean hasBodyRequired = false;
        for (JsonNode n : requiredNode) {
            if ("body".equals(locations.get(n.asText()))) {
                hasBodyRequired = true;
                break;
            }
        }
        if (!hasBodyRequired) return schema;
        ObjectNode copy = (ObjectNode) schema.deepCopy();
        ArrayNode filtered = mapper.createArrayNode();
        for (JsonNode n : requiredNode) {
            if (!"body".equals(locations.get(n.asText()))) filtered.add(n);
        }
        copy.set("required", filtered);
        return copy;
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

    private static String originOf(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
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

    /**
     * A tool's own persisted auth override (if set) takes precedence over its connection's stored
     * auth — this is a per-endpoint admin decision (e.g. one endpoint needs a different API key
     * than the rest of the app), distinct from the ephemeral, GET-only {@link #applyAuthOverride}.
     */
    private void applyAuth(HttpRequest.Builder request, Connection connection, ApiTool tool) {
        if (tool.authMode() != null) {
            applyAuthMode(request, tool.authMode(), tool.authUsername(), tool.authSecretEncrypted());
        } else {
            applyAuthMode(request, connection.authMode(), connection.authUsername(), connection.authSecretEncrypted());
        }
    }

    private void applyAuthMode(HttpRequest.Builder request, com.mcpserver.connectors.AuthMode mode,
                               String username, String secretEncrypted) {
        String secret = secretEncrypted == null ? null : credentialCipher.decrypt(secretEncrypted);
        switch (mode) {
            case BASIC -> {
                if (secret != null) {
                    String token = Base64.getEncoder().encodeToString(
                            (username + ":" + secret).getBytes(StandardCharsets.UTF_8));
                    request.header("Authorization", "Basic " + token);
                }
            }
            case BEARER -> {
                if (secret != null) request.header("Authorization", "Bearer " + secret);
            }
            case API_KEY_HEADER -> {
                if (secret != null && username != null) {
                    request.header(username, secret);
                }
            }
            case NONE, OAUTH2 -> {
                // NONE sends nothing; OAUTH2 is a reserved extension point (unimplemented)
            }
        }
    }

    /** Per-invocation auth override — read tools only, see {@link #executeRaw} guard. Never persisted. */
    private void applyAuthOverride(HttpRequest.Builder request, AuthOverride auth) {
        switch (auth.mode() == null ? "NONE" : auth.mode()) {
            case "BASIC" -> {
                if (auth.secret() != null) {
                    String token = Base64.getEncoder().encodeToString(
                            (auth.username() + ":" + auth.secret()).getBytes(StandardCharsets.UTF_8));
                    request.setHeader("Authorization", "Basic " + token);
                }
            }
            case "BEARER" -> {
                if (auth.secret() != null) request.setHeader("Authorization", "Bearer " + auth.secret());
            }
            case "API_KEY_HEADER" -> {
                if (auth.secret() != null && auth.headerName() != null) {
                    request.setHeader(auth.headerName(), auth.secret());
                }
            }
            default -> {
                // NONE — send nothing, not even the connection's stored auth
            }
        }
    }

    private ToolInvocationResult toResult(HttpResponse<byte[]> response, long latency, String summary) {
        byte[] raw = response.body();
        boolean truncated = raw.length > MAX_RESPONSE_BYTES;
        String text = new String(raw, 0, Math.min(raw.length, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        Map<String, String> headers = new HashMap<>();
        response.headers().map().forEach((name, values) -> headers.put(name, String.join(", ", values)));

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
        return new ToolInvocationResult(response.statusCode(), latency, contentType, text, truncated, summary, headers);
    }

    /**
     * Validates and renders the request a tool <em>would</em> send — method, resolved URL,
     * headers, body — without sending it. Backs the preview→approve step (§5.8/§7.2) for
     * state-changing tools, the live "resolved request" readout, and code-snippet generation.
     * The real Authorization value is never included — masked when the connection (or an
     * override) would add one.
     */
    public Map<String, Object> renderPreview(ApiTool tool, Connection connection, Map<String, Object> args)
            throws Exception {
        return renderPreview(tool, connection, args, InvokeOverrides.empty());
    }

    public Map<String, Object> renderPreview(ApiTool tool, Connection connection, Map<String, Object> args,
                                             InvokeOverrides overrides) throws Exception {
        if (overrides.auth() != null && !tool.isRead()) {
            throw new IllegalArgumentException(
                    "Auth override is only available for read (GET) tools");
        }
        JsonNode schema = mapper.readTree(tool.paramsSchema());
        Map<String, String> locations = readStringMap(tool.paramLocations());
        Map<String, Object> merged = mergeDefaults(schema, args);
        List<SchemaValidator.Violation> violations =
                SchemaValidator.validate(effectiveSchemaForValidation(schema, locations, overrides), merged);
        if (!violations.isEmpty()) {
            throw new ToolValidationException(violations);
        }
        Map<String, Object> preview = new HashMap<>();
        preview.put("method", tool.httpMethod());
        preview.put("url", renderUri(tool, connection, merged, locations, overrides.extraQueryParams()).toString());

        Map<String, String> headers = new HashMap<>(readStringMap(tool.headers()));
        for (var e : merged.entrySet()) {
            if ("header".equals(locations.get(e.getKey())) && e.getValue() != null) {
                headers.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        headers.putAll(overrides.extraHeaders());
        if ("RAW".equals(overrides.bodyMode()) && overrides.rawContentType() != null
                && !overrides.rawContentType().isBlank()) {
            headers.put("Content-Type", overrides.rawContentType());
        }
        if (overrides.auth() != null) {
            String mode = overrides.auth().mode();
            if ("BASIC".equals(mode) || "BEARER".equals(mode)) {
                headers.put("Authorization", "<will be sent — not shown here>");
            } else if ("API_KEY_HEADER".equals(mode) && overrides.auth().headerName() != null) {
                headers.put(overrides.auth().headerName(), "<will be sent — not shown here>");
            }
        } else {
            com.mcpserver.connectors.AuthMode effectiveMode = tool.authMode() != null ? tool.authMode() : connection.authMode();
            String effectiveUsername = tool.authMode() != null ? tool.authUsername() : connection.authUsername();
            switch (effectiveMode) {
                case BASIC, BEARER -> headers.put("Authorization", "<will be sent — not shown here>");
                case API_KEY_HEADER -> {
                    if (effectiveUsername != null) {
                        headers.put(effectiveUsername, "<will be sent — not shown here>");
                    }
                }
                default -> { /* NONE, OAUTH2 — nothing to mask */ }
            }
        }
        preview.put("headers", headers);

        String body = renderBody(tool, merged, locations, overrides);
        if (body != null) preview.put("body", body);
        if ("RAW".equals(overrides.bodyMode()) && overrides.rawContentType() != null) {
            preview.put("contentType", overrides.rawContentType());
        }
        return preview;
    }

    /** Raw response bytes for knowledge-source ingestion (no pretty-print/display cap). */
    public byte[] executeForIngestion(ApiTool tool, Connection connection) throws Exception {
        RawResult raw = executeRaw(tool, connection, Map.of(), InvokeOverrides.empty());
        if (raw.response().statusCode() >= 400) {
            throw new IllegalStateException("Tool " + tool.name() + " returned HTTP " + raw.response().statusCode());
        }
        return raw.response().body();
    }
}
