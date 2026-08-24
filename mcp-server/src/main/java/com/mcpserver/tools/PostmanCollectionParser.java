package com.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcpserver.connectors.AuthMode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Postman collection v2.1 → {@link ApiToolDefinition}s. Walks the {@code item} tree (folders
 * recurse, leaves carry {@code request}). Postman has no formal input schema, so parameters are
 * inferred: path {@code :vars} and unresolved {@code {{vars}}} become required string properties,
 * query params optional ones, and example JSON body keys typed by value inference.
 */
@Component
public class PostmanCollectionParser implements SpecParser {

    /** Variables conventionally holding the API root declared by the collection. */
    private static final Pattern BASE_URL_VAR =
            Pattern.compile("^\\{\\{(baseUrl|base_url|host|url|server)}}", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{([^}]+)}}");
    private static final Pattern PATH_VAR = Pattern.compile("(?<=/):([A-Za-z_][A-Za-z0-9_]*)");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(JsonNode root) {
        if (root == null) return false;
        JsonNode info = root.path("info");
        return root.has("item")
                && (info.has("_postman_id")
                    || info.path("schema").asText("").contains("getpostman.com")
                    || containsRequest(root.path("item")));
    }

    @Override
    public String format() {
        return "POSTMAN";
    }

    @Override
    public List<ApiToolDefinition> parse(JsonNode root) {
        return parse(root, false);
    }

    @Override
    public List<ApiToolDefinition> parse(JsonNode root, boolean preserveSourceUrls) {
        List<ApiToolDefinition> out = new ArrayList<>();
        walk(root.path("item"), "", collectVariables(root), preserveSourceUrls, out);
        return out;
    }

    /**
     * Postman collections have no {@code servers} block. Prefer a collection-level
     * {@code variable} named baseUrl/base_url/host/url/server (the {@code {{var}}} convention
     * every request in the collection would reference); otherwise fall back to the origin of the
     * first request that hardcodes an absolute URL — common for collections exported against a
     * single fixed host instead of a templated one.
     */
    @Override
    public String extractBaseUrl(JsonNode root) {
        Map<String, String> variables = collectVariables(root);
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            String key = variable.getKey();
            String value = variable.getValue();
            if (!value.isBlank() && BASE_URL_VAR.matcher("{{" + key + "}}").find()
                    && value.matches("^https?://.*")) {
                return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
            }
        }
        return firstAbsoluteOrigin(root.path("item"), variables);
    }

    private String firstAbsoluteOrigin(JsonNode items, Map<String, String> variables) {
        if (!items.isArray()) return null;
        for (JsonNode item : items) {
            if (item.has("item")) {
                String found = firstAbsoluteOrigin(item.path("item"), variables);
                if (found != null) return found;
            } else if (item.has("request")) {
                JsonNode url = item.path("request").path("url");
                String raw = url.isTextual() ? url.asText() : url.path("raw").asText("");
                raw = resolveVariables(raw, variables);
                if (raw.matches("^https?://.*")) {
                    try {
                        URI uri = URI.create(raw);
                        if (uri.getHost() != null) {
                            return uri.getScheme() + "://" + uri.getHost()
                                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
                        }
                    } catch (IllegalArgumentException ignored) {
                        // malformed URL in this request — keep looking
                    }
                }
            }
        }
        return null;
    }

    private boolean containsRequest(JsonNode items) {
        if (!items.isArray()) return false;
        for (JsonNode item : items) {
            if (item.has("request")) return true;
            if (item.has("item") && containsRequest(item.path("item"))) return true;
        }
        return false;
    }

    /** Header names conventionally used for an API key when no explicit Postman auth block exists. */
    private static final List<String> API_KEY_HEADER_NAMES = List.of(
            "x-api-key", "api-key", "x-auth-token", "x-auth-key", "ocp-apim-subscription-key");
    private static final Pattern BEARER_HEADER =
            Pattern.compile("^bearer\\s+\\{\\{([^}]+)}}$", Pattern.CASE_INSENSITIVE);

    /**
     * Prefers the collection's own {@code auth} block (precise — Postman's own convention);
     * falls back to a best-effort header scan when there's no explicit block, so collections that
     * just hardcode a credential header still get a suggestion. Never resolves a secret value —
     * only the auth <em>mode</em> and, for BASIC/API_KEY_HEADER, a non-secret field identity
     * (a literal username, or the header/query name itself).
     */
    @Override
    public DetectedAuth detectAuth(JsonNode root) {
        Map<String, String> variables = collectVariables(root);
        DetectedAuth fromBlock = detectFromAuthBlock(root.path("auth"), variables);
        if (fromBlock != null) return fromBlock;
        DetectedAuth fromHeaders = scanHeadersForAuth(root.path("item"));
        return fromHeaders != null ? fromHeaders : DetectedAuth.NONE;
    }

    private Map<String, String> collectVariables(JsonNode root) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (JsonNode variable : root.path("variable")) {
            String key = variable.path("key").asText("");
            if (!key.isBlank()) vars.put(key, variable.path("value").asText(""));
        }
        return vars;
    }

    /** A literal (non-templated), non-blank value for the variable {@code varRef} references, or null. */
    private String literalValue(Map<String, String> variables, String varRef) {
        Matcher m = TEMPLATE_VAR.matcher(varRef);
        String varName = m.matches() ? m.group(1).trim() : varRef;
        String value = variables.get(varName);
        if (value == null || value.isBlank() || TEMPLATE_VAR.matcher(value).find()) return null;
        return value;
    }

    /** Null return means "no recognizable auth block" (missing, {@code noauth}, or an unhandled type) — the caller falls back to a header scan. */
    private DetectedAuth detectFromAuthBlock(JsonNode auth, Map<String, String> variables) {
        String type = auth.path("type").asText("");
        return switch (type) {
            case "basic" -> {
                String usernameRef = authField(auth.path("basic"), "username");
                yield new DetectedAuth(AuthMode.BASIC, usernameRef == null ? null : literalValue(variables, usernameRef));
            }
            case "bearer" -> new DetectedAuth(AuthMode.BEARER, null);
            case "apikey" -> {
                String headerName = authField(auth.path("apikey"), "key");
                yield headerName == null ? null : new DetectedAuth(AuthMode.API_KEY_HEADER, headerName);
            }
            default -> null;
        };
    }

    /** Postman auth blocks are arrays of {@code {key, value, type}} triples — finds {@code fieldKey}'s value. */
    private String authField(JsonNode authParams, String fieldKey) {
        if (!authParams.isArray()) return null;
        for (JsonNode entry : authParams) {
            if (fieldKey.equals(entry.path("key").asText(""))) {
                String value = entry.path("value").asText("");
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    /** No explicit auth block — scans every request's headers for a Bearer token or a known
     * API-key header name, stopping at the first hit. */
    private DetectedAuth scanHeadersForAuth(JsonNode items) {
        if (!items.isArray()) return null;
        for (JsonNode item : items) {
            if (item.has("item")) {
                DetectedAuth found = scanHeadersForAuth(item.path("item"));
                if (found != null) return found;
            } else if (item.has("request")) {
                for (JsonNode header : item.path("request").path("header")) {
                    String key = header.path("key").asText("");
                    String value = header.path("value").asText("");
                    if (key.isBlank() || value.isBlank()) continue;
                    if (key.equalsIgnoreCase("Authorization")) {
                        if (BEARER_HEADER.matcher(value.trim()).matches()) {
                            return new DetectedAuth(AuthMode.BEARER, null);
                        }
                    } else if (API_KEY_HEADER_NAMES.contains(key.toLowerCase(Locale.ROOT))
                            && TEMPLATE_VAR.matcher(value).find()) {
                        return new DetectedAuth(AuthMode.API_KEY_HEADER, key);
                    }
                }
            }
        }
        return null;
    }

    private void walk(JsonNode items, String folderPath, Map<String, String> variables,
                      boolean preserveSourceUrls,
                      List<ApiToolDefinition> out) {
        if (!items.isArray()) return;
        for (JsonNode item : items) {
            String name = item.path("name").asText("unnamed");
            if (item.has("item")) {
                String childPath = folderPath.isEmpty() ? name : folderPath + "/" + name;
                walk(item.path("item"), childPath, variables, preserveSourceUrls, out);
            } else if (item.has("request")) {
                out.add(toDefinition(item, name, folderPath, variables, preserveSourceUrls));
            }
        }
    }

    private ApiToolDefinition toDefinition(JsonNode item, String name, String folderPath,
                                           Map<String, String> variables,
                                           boolean preserveSourceUrls) {
        JsonNode request = item.path("request");
        String method = request.path("method").asText("GET").toUpperCase();
        String description = request.path("description").isObject()
                ? request.path("description").path("content").asText("")
                : request.path("description").asText("");

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        Map<String, String> locations = new LinkedHashMap<>();

        String urlTemplate = parseUrl(
                request.path("url"), variables, preserveSourceUrls,
                properties, required, locations);
        Map<String, String> staticHeaders = parseHeaders(request.path("header"), properties, required, locations);
        String bodyTemplate = parseBody(request.path("body"), properties, required, locations, staticHeaders);
        if (required.isEmpty()) schema.remove("required");

        String category = folderPath.isEmpty() ? "general" : folderPath;
        return new ApiToolDefinition(name, Slugifier.slug(name), description, category, method,
                urlTemplate, schema, locations, staticHeaders, bodyTemplate,
                OpenApiParser.primaryParam(properties, required, locations));
    }

    /**
     * Normalizes the request URL to a path template. The default mode strips a leading
     * {@code {{baseUrl}}}-style variable or absolute origin; source-URL mode retains each resolved
     * absolute origin. {@code :var} and inline {@code {{var}}} path segments become template params.
     */
    private String parseUrl(JsonNode url, Map<String, String> variables,
                            boolean preserveSourceUrls,
                            ObjectNode properties, ArrayNode required,
                            Map<String, String> locations) {
        String raw = url.isTextual() ? url.asText() : url.path("raw").asText("/");
        raw = resolveVariables(raw, variables);

        String pathPart = raw;
        Matcher baseVar = BASE_URL_VAR.matcher(pathPart);
        if (preserveSourceUrls) {
            // A conventional but unresolved {{baseUrl}} is not itself a source host. Keep the
            // request path and let ApiCollectionConnector resolve it against the API URL detected
            // from this collection. Other unresolved authority variables are unsafe to preserve.
            if (baseVar.find()) {
                pathPart = pathPart.substring(baseVar.end());
            } else if (pathPart.matches("^\\{\\{[^}]+}}.*")) {
                throw new IllegalArgumentException("Cannot preserve an unresolved Postman URL variable in "
                        + raw + " — define it at collection level or use connection-base mode");
            }
        } else {
            if (baseVar.find()) {
                pathPart = pathPart.substring(baseVar.end());
            } else if (pathPart.matches("^https?://.*")) {
                int slash = pathPart.indexOf('/', pathPart.indexOf("//") + 2);
                pathPart = slash >= 0 ? pathPart.substring(slash) : "/";
            }
        }
        int q = pathPart.indexOf('?');
        if (q >= 0) pathPart = pathPart.substring(0, q);
        int fragment = pathPart.indexOf('#');
        if (fragment >= 0) pathPart = pathPart.substring(0, fragment);
        if (!pathPart.startsWith("/") && !pathPart.matches("^https?://.*")) {
            pathPart = "/" + pathPart;
        }

        // :var → {var}
        Matcher pathVars = PATH_VAR.matcher(pathPart);
        while (pathVars.find()) {
            addParam(pathVars.group(1), "path", true, properties, required, locations);
        }
        pathPart = pathVars.replaceAll("{$1}");

        // remaining {{var}} in the path → {var}, required
        Matcher templateVars = TEMPLATE_VAR.matcher(pathPart);
        while (templateVars.find()) {
            addParam(Slugifier.slug(templateVars.group(1)), "path", true, properties, required, locations);
        }
        pathPart = replaceTemplateVars(pathPart);

        if (url.isObject()) {
            for (JsonNode queryParam : url.path("query")) {
                if (queryParam.path("disabled").asBoolean(false)) continue;
                String key = queryParam.path("key").asText("");
                if (key.isBlank()) continue;
                addParam(key, "query", false, properties, required, locations);
                String value = queryParam.path("value").asText("");
                String desc = queryParam.path("description").asText("");
                ObjectNode prop = (ObjectNode) properties.get(key);
                if (!desc.isBlank()) prop.put("description", desc);
                if (!value.isBlank() && !TEMPLATE_VAR.matcher(value).find()) {
                    prop.put("default", value);
                }
            }
        }
        return pathPart;
    }

    private static String resolveVariables(String raw, Map<String, String> variables) {
        String resolved = raw;
        for (int pass = 0; pass < 5; pass++) {
            String before = resolved;
            Matcher matcher = TEMPLATE_VAR.matcher(resolved);
            resolved = matcher.replaceAll(match -> {
                String value = variables.get(match.group(1).trim());
                return value == null ? Matcher.quoteReplacement(match.group()) : Matcher.quoteReplacement(value);
            });
            if (resolved.equals(before)) break;
        }
        return resolved;
    }

    private Map<String, String> parseHeaders(JsonNode headers, ObjectNode properties,
                                             ArrayNode required, Map<String, String> locations) {
        Map<String, String> staticHeaders = new LinkedHashMap<>();
        if (!headers.isArray()) return staticHeaders;
        for (JsonNode header : headers) {
            if (header.path("disabled").asBoolean(false)) continue;
            String key = header.path("key").asText("");
            String value = header.path("value").asText("");
            if (key.isBlank() || key.equalsIgnoreCase("Authorization")) continue;
            if (TEMPLATE_VAR.matcher(value).find()) {
                addParam(key, "header", false, properties, required, locations);
            } else {
                staticHeaders.put(key, value);
            }
        }
        return staticHeaders;
    }

    private String parseBody(JsonNode body, ObjectNode properties, ArrayNode required,
                             Map<String, String> locations, Map<String, String> staticHeaders) {
        String mode = body.path("mode").asText("");
        if ("urlencoded".equals(mode)) {
            return parseUrlEncodedBody(body.path("urlencoded"), properties, required, locations, staticHeaders);
        }
        if ("graphql".equals(mode)) {
            ObjectNode payload = mapper.createObjectNode();
            JsonNode graphql = body.path("graphql");
            payload.put("query", graphql.path("query").asText(""));
            String variables = graphql.path("variables").asText("");
            if (!variables.isBlank()) {
                try {
                    payload.set("variables", mapper.readTree(variables));
                } catch (Exception ignored) {
                    payload.put("variables", variables);
                }
            }
            return parseRawBody(payload.toString(), "application/json",
                    properties, required, locations, staticHeaders);
        }
        if (!"raw".equals(mode)) return null;
        String language = body.path("options").path("raw").path("language").asText("");
        String contentType = switch (language) {
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "javascript" -> "application/javascript";
            case "html" -> "text/html";
            default -> "text/plain";
        };
        return parseRawBody(body.path("raw").asText(""), contentType,
                properties, required, locations, staticHeaders);
    }

    private String parseRawBody(String raw, String contentType, ObjectNode properties,
                                ArrayNode required, Map<String, String> locations,
                                Map<String, String> staticHeaders) {
        if (raw.isBlank()) return null;

        String template = replaceTemplateVars(raw);

        JsonNode example;
        try {
            example = mapper.readTree(template);
        } catch (Exception e) {
            staticHeaders.putIfAbsent("Content-Type", contentType);
            addRawTemplateParams(raw, properties, required, locations);
            return template;
        }
        staticHeaders.putIfAbsent("Content-Type",
                example.isContainerNode() && "text/plain".equals(contentType)
                        ? "application/json" : contentType);
        if (!example.isObject()) {
            addRawTemplateParams(raw, properties, required, locations);
            return template;
        }

        example.properties().forEach(e -> {
            String key = e.getKey();
            JsonNode value = e.getValue();
            if (!properties.has(key)) {
                ObjectNode prop = properties.putObject(key);
                prop.put("type", inferType(value));
                locations.put(key, "body");
                required.add(key); // example bodies show what the endpoint expects — treat as required
            }
        });
        return example.toString();
    }

    private void addRawTemplateParams(String raw, ObjectNode properties, ArrayNode required,
                                      Map<String, String> locations) {
        Matcher variables = TEMPLATE_VAR.matcher(raw);
        while (variables.find()) {
            addParam(Slugifier.slug(variables.group(1)), "body", false, properties, required, locations);
        }
    }

    private String parseUrlEncodedBody(JsonNode fields, ObjectNode properties, ArrayNode required,
                                       Map<String, String> locations, Map<String, String> staticHeaders) {
        if (!fields.isArray()) return null;
        List<String> pairs = new ArrayList<>();
        for (JsonNode field : fields) {
            if (field.path("disabled").asBoolean(false)) continue;
            String key = field.path("key").asText("");
            if (key.isBlank()) continue;
            String paramName = Slugifier.slug(key);
            addParam(paramName, "body", false, properties, required, locations);
            String value = field.path("value").asText("");
            if (!value.isBlank() && !TEMPLATE_VAR.matcher(value).find()) {
                ((ObjectNode) properties.get(paramName)).put("default", value);
            }
            String description = field.path("description").asText("");
            if (!description.isBlank()) {
                ((ObjectNode) properties.get(paramName)).put("description", description);
            }
            pairs.add(key + "={" + paramName + "}");
        }
        if (pairs.isEmpty()) return null;
        staticHeaders.put("Content-Type", "application/x-www-form-urlencoded");
        return String.join("&", pairs);
    }

    private void addParam(String name, String location, boolean isRequired,
                          ObjectNode properties, ArrayNode required, Map<String, String> locations) {
        if (locations.containsKey(name)) return;
        properties.putObject(name).put("type", "string");
        locations.put(name, location);
        if (isRequired) required.add(name);
    }

    /** {@code {{var}}} → {@code {var}} (slugified) — template-param form for URLs and bodies. */
    private static String replaceTemplateVars(String raw) {
        return TEMPLATE_VAR.matcher(raw).replaceAll(m ->
                "{" + Slugifier.slug(m.group(1)) + "}");
    }

    private static String inferType(JsonNode value) {
        if (value.isInt() || value.isLong()) return "integer";
        if (value.isNumber()) return "number";
        if (value.isBoolean()) return "boolean";
        if (value.isArray()) return "array";
        if (value.isObject()) return "object";
        return "string";
    }
}
