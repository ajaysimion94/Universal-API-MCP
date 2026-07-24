package com.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Variables conventionally holding the API root — replaced by the connection's baseUrl. */
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
                && (info.has("_postman_id") || info.path("schema").asText("").contains("getpostman.com"));
    }

    @Override
    public String format() {
        return "POSTMAN";
    }

    @Override
    public List<ApiToolDefinition> parse(JsonNode root) {
        List<ApiToolDefinition> out = new ArrayList<>();
        walk(root.path("item"), "", out);
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
        for (JsonNode variable : root.path("variable")) {
            String key = variable.path("key").asText("");
            String value = variable.path("value").asText("");
            if (!value.isBlank() && BASE_URL_VAR.matcher("{{" + key + "}}").find()
                    && value.matches("^https?://.*")) {
                return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
            }
        }
        return firstAbsoluteOrigin(root.path("item"));
    }

    private String firstAbsoluteOrigin(JsonNode items) {
        if (!items.isArray()) return null;
        for (JsonNode item : items) {
            if (item.has("item")) {
                String found = firstAbsoluteOrigin(item.path("item"));
                if (found != null) return found;
            } else if (item.has("request")) {
                JsonNode url = item.path("request").path("url");
                String raw = url.isTextual() ? url.asText() : url.path("raw").asText("");
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

    private void walk(JsonNode items, String folderPath, List<ApiToolDefinition> out) {
        if (!items.isArray()) return;
        for (JsonNode item : items) {
            String name = item.path("name").asText("unnamed");
            if (item.has("item")) {
                String childPath = folderPath.isEmpty() ? name : folderPath + "/" + name;
                walk(item.path("item"), childPath, out);
            } else if (item.has("request")) {
                out.add(toDefinition(item, name, folderPath));
            }
        }
    }

    private ApiToolDefinition toDefinition(JsonNode item, String name, String folderPath) {
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

        String urlTemplate = parseUrl(request.path("url"), properties, required, locations);
        Map<String, String> staticHeaders = parseHeaders(request.path("header"), properties, required, locations);
        String bodyTemplate = parseBody(request.path("body"), properties, required, locations, staticHeaders);
        if (required.isEmpty()) schema.remove("required");

        String category = folderPath.isEmpty() ? "general" : folderPath;
        return new ApiToolDefinition(name, Slugifier.slug(name), description, category, method,
                urlTemplate, schema, locations, staticHeaders, bodyTemplate,
                OpenApiParser.primaryParam(properties, required, locations));
    }

    /**
     * Normalizes the request URL to a path template relative to the connection's baseUrl:
     * a leading {@code {{baseUrl}}}-style variable or absolute origin is stripped, {@code :var}
     * and inline {@code {{var}}} path segments become {@code {var}} template params.
     */
    private String parseUrl(JsonNode url, ObjectNode properties, ArrayNode required,
                            Map<String, String> locations) {
        String raw = url.isTextual() ? url.asText() : url.path("raw").asText("/");

        String pathPart = raw;
        Matcher baseVar = BASE_URL_VAR.matcher(pathPart);
        if (baseVar.find()) {
            pathPart = pathPart.substring(baseVar.end());
        } else if (pathPart.matches("^https?://.*")) {
            int slash = pathPart.indexOf('/', pathPart.indexOf("//") + 2);
            pathPart = slash >= 0 ? pathPart.substring(slash) : "/";
        }
        int q = pathPart.indexOf('?');
        if (q >= 0) pathPart = pathPart.substring(0, q);
        if (!pathPart.startsWith("/")) pathPart = "/" + pathPart;

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
        if (!"raw".equals(body.path("mode").asText(""))) return null;
        String raw = body.path("raw").asText("");
        if (raw.isBlank()) return null;

        JsonNode example;
        try {
            example = mapper.readTree(replaceTemplateVars(raw));
        } catch (Exception e) {
            return null; // non-JSON raw body — no inferable parameters
        }
        if (!example.isObject()) return null;

        staticHeaders.putIfAbsent("Content-Type", "application/json");
        example.properties().forEach(e -> {
            String key = e.getKey();
            JsonNode value = e.getValue();
            ObjectNode prop = properties.putObject(key);
            prop.put("type", inferType(value));
            locations.put(key, "body");
            required.add(key); // example bodies show what the endpoint expects — treat as required
        });
        return example.toString();
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
