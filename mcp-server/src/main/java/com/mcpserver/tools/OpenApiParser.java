package com.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 3.x / Swagger 2.0 → {@link ApiToolDefinition}s. Walks paths → operations, flattening
 * path/query/header parameters and JSON request-body schemas into one flat object schema. Local
 * {@code $ref}s are resolved with a depth cap; external refs degrade to {@code {"type":"object"}}.
 */
@Component
public class OpenApiParser implements SpecParser {

    private static final int MAX_REF_DEPTH = 10;
    private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(JsonNode root) {
        return root != null && root.has("paths")
                && (root.has("openapi") || root.path("swagger").asText("").startsWith("2."));
    }

    @Override
    public String format() {
        return "OPENAPI";
    }

    /** OpenAPI servers[0].url or Swagger 2 schemes/host/basePath. */
    public static String extractServerUrl(JsonNode root) {
        JsonNode servers = root.path("servers");
        if (servers.isArray() && !servers.isEmpty()) {
            String url = servers.get(0).path("url").asText("");
            if (!url.isBlank()) return url;
        }
        if (root.path("swagger").asText("").startsWith("2.")) {
            String host = root.path("host").asText("");
            String basePath = root.path("basePath").asText("");
            if (!basePath.isBlank() && !basePath.startsWith("/")) basePath = "/" + basePath;
            if (!host.isBlank()) {
                String scheme = "https";
                JsonNode schemes = root.path("schemes");
                if (schemes.isArray() && !schemes.isEmpty()
                        && !schemes.get(0).asText("").isBlank()) {
                    scheme = schemes.get(0).asText();
                }
                return scheme + "://" + host + basePath;
            }
            if (!basePath.isBlank()) return basePath;
        }
        return null;
    }

    @Override
    public String extractBaseUrl(JsonNode root) {
        return extractServerUrl(root);
    }

    @Override
    public List<ApiToolDefinition> parse(JsonNode root) {
        List<ApiToolDefinition> out = new ArrayList<>();
        JsonNode paths = root.path("paths");
        paths.properties().forEach(pathEntry -> {
            String path = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();
            JsonNode pathLevelParams = pathItem.path("parameters");
            for (String method : METHODS) {
                JsonNode op = pathItem.path(method);
                if (op.isMissingNode() || !op.isObject()) continue;
                out.add(toDefinition(root, path, method, op, pathLevelParams));
            }
        });
        return out;
    }

    private ApiToolDefinition toDefinition(JsonNode root, String path, String method,
                                           JsonNode op, JsonNode pathLevelParams) {
        String summary = op.path("summary").asText("");
        String operationId = op.path("operationId").asText("");
        String displayName = !summary.isBlank() ? summary
                : !operationId.isBlank() ? operationId
                : method.toUpperCase() + " " + path;
        String requestSlug = Slugifier.slug(!operationId.isBlank() ? operationId : displayName);
        String description = op.path("description").asText(summary);

        String category = "general";
        JsonNode tags = op.path("tags");
        if (tags.isArray() && !tags.isEmpty()) {
            category = tags.get(0).asText("general");
        } else {
            category = firstPathSegment(path);
        }

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        Map<String, String> locations = new LinkedHashMap<>();

        collectParameters(root, pathLevelParams, properties, required, locations);
        collectParameters(root, op.path("parameters"), properties, required, locations);

        String bodyTemplate = root.path("swagger").asText("").startsWith("2.")
                ? collectSwaggerBody(root, pathLevelParams, op.path("parameters"),
                        properties, required, locations)
                : collectRequestBody(root, op, properties, required, locations);
        if (required.isEmpty()) schema.remove("required");

        return new ApiToolDefinition(displayName, requestSlug, description, category,
                method.toUpperCase(), path, schema, locations, staticHeadersFor(bodyTemplate),
                bodyTemplate, primaryParam(properties, required, locations));
    }

    private String collectSwaggerBody(JsonNode root, JsonNode pathLevelParams, JsonNode opParams,
                                      ObjectNode properties, ArrayNode required,
                                      Map<String, String> locations) {
        JsonNode bodyParam = findSwaggerBodyParameter(root, opParams);
        if (bodyParam == null) bodyParam = findSwaggerBodyParameter(root, pathLevelParams);
        if (bodyParam == null) return null;

        JsonNode bodySchema = resolveRef(root, bodyParam.path("schema"), 0);
        return collectBodySchema(root, bodySchema, bodyParam.path("required").asBoolean(false),
                properties, required, locations);
    }

    private JsonNode findSwaggerBodyParameter(JsonNode root, JsonNode params) {
        if (!params.isArray()) return null;
        for (JsonNode raw : params) {
            JsonNode param = resolveRef(root, raw, 0);
            if ("body".equals(param.path("in").asText(""))) return param;
        }
        return null;
    }

    private void collectParameters(JsonNode root, JsonNode params, ObjectNode properties,
                                   ArrayNode required, Map<String, String> locations) {
        if (!params.isArray()) return;
        for (JsonNode raw : params) {
            JsonNode param = resolveRef(root, raw, 0);
            String name = param.path("name").asText("");
            String in = param.path("in").asText("");
            if (name.isBlank() || in.equals("cookie")) continue;
            if (!in.equals("path") && !in.equals("query") && !in.equals("header")) continue;

            ObjectNode prop = properties.putObject(name);
            // OpenAPI 3 puts type/default/enum under parameter.schema; Swagger 2 keeps them
            // directly on the parameter object.
            JsonNode paramSchema = param.has("schema")
                    ? resolveRef(root, param.path("schema"), 0)
                    : param;
            prop.put("type", jsonType(paramSchema.path("type").asText("string")));
            String desc = param.path("description").asText("");
            if (!desc.isBlank()) prop.put("description", desc);
            if (paramSchema.has("enum")) prop.set("enum", paramSchema.get("enum"));
            if (paramSchema.has("default")) prop.set("default", paramSchema.get("default"));

            locations.put(name, in);
            if (param.path("required").asBoolean(false) || in.equals("path")) {
                required.add(name);
            }
        }
    }

    /** Returns a skeleton body template (or null) while adding body properties to the schema. */
    private String collectRequestBody(JsonNode root, JsonNode op, ObjectNode properties,
                                      ArrayNode required, Map<String, String> locations) {
        JsonNode requestBody = resolveRef(root, op.path("requestBody"), 0);
        JsonNode content = requestBody.path("content");
        JsonNode media = content.path("application/json");
        if (media.isMissingNode()) {
            // take the first JSON-ish media type if application/json isn't declared verbatim
            var it = content.properties().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getKey().contains("json")) {
                    media = e.getValue();
                    break;
                }
            }
        }
        if (media.isMissingNode()) return null;

        JsonNode bodySchema = resolveRef(root, media.path("schema"), 0);
        if (bodySchema.isMissingNode()) return null;
        return collectBodySchema(root, bodySchema,
                requestBody.path("required").asBoolean(false), properties, required, locations);
    }

    private String collectBodySchema(JsonNode root, JsonNode bodySchema, boolean bodyRequired,
                                     ObjectNode properties, ArrayNode required,
                                     Map<String, String> locations) {
        List<String> requiredBodyProps = new ArrayList<>();
        JsonNode req = bodySchema.path("required");
        if (req.isArray()) req.forEach(n -> requiredBodyProps.add(n.asText()));

        JsonNode bodyProps = bodySchema.path("properties");
        ObjectNode template = mapper.createObjectNode();
        if (bodyProps.isObject()) {
            bodyProps.properties().forEach(e -> {
                String name = e.getKey();
                JsonNode propSchema = resolveRef(root, e.getValue(), 0);
                ObjectNode prop = properties.putObject(name);
                prop.put("type", jsonType(propSchema.path("type").asText("string")));
                String desc = propSchema.path("description").asText("");
                if (!desc.isBlank()) prop.put("description", desc);
                if (propSchema.has("enum")) prop.set("enum", propSchema.get("enum"));
                if (propSchema.has("default")) prop.set("default", propSchema.get("default"));
                locations.put(name, "body");
                if (requiredBodyProps.contains(name)) required.add(name);
                template.set(name, exampleValue(propSchema));
            });
        } else {
            // schema without properties (e.g. free-form object or array) — single "body" property
            properties.putObject("body").put("type", "object")
                    .put("description", "Raw JSON request body");
            locations.put("body", "body");
            if (bodyRequired) required.add("body");
        }
        return template.isEmpty() ? null : template.toString();
    }

    private JsonNode exampleValue(JsonNode propSchema) {
        if (propSchema.has("example")) return propSchema.get("example");
        if (propSchema.has("default")) return propSchema.get("default");
        return switch (propSchema.path("type").asText("string")) {
            case "integer", "number" -> mapper.getNodeFactory().numberNode(0);
            case "boolean" -> mapper.getNodeFactory().booleanNode(false);
            case "array" -> mapper.createArrayNode();
            case "object" -> mapper.createObjectNode();
            default -> mapper.getNodeFactory().textNode("");
        };
    }

    /**
     * Resolves local {@code $ref}s ({@code #/components/...}, {@code #/definitions/...}) with
     * cycle/depth protection; external refs and over-deep chains degrade to an empty object node.
     */
    private JsonNode resolveRef(JsonNode root, JsonNode node, int depth) {
        if (node == null || node.isMissingNode() || !node.isObject() || !node.has("$ref")) {
            return node == null ? mapper.missingNode() : node;
        }
        if (depth >= MAX_REF_DEPTH) return mapper.createObjectNode();
        String ref = node.get("$ref").asText("");
        if (!ref.startsWith("#/")) return mapper.createObjectNode();
        JsonNode target = root;
        for (String part : ref.substring(2).split("/")) {
            target = target.path(part.replace("~1", "/").replace("~0", "~"));
        }
        return resolveRef(root, target, depth + 1);
    }

    private static String jsonType(String openApiType) {
        return switch (openApiType) {
            case "integer" -> "integer";
            case "number" -> "number";
            case "boolean" -> "boolean";
            case "array" -> "array";
            case "object" -> "object";
            default -> "string";
        };
    }

    private static String firstPathSegment(String path) {
        for (String segment : path.split("/")) {
            if (!segment.isBlank() && !segment.startsWith("{")
                    && !segment.equals("api") && !segment.matches("v\\d+")) {
                return segment;
            }
        }
        return "general";
    }

    private static Map<String, String> staticHeadersFor(String bodyTemplate) {
        return bodyTemplate != null ? Map.of("Content-Type", "application/json") : Map.of();
    }

    static String primaryParam(ObjectNode properties, ArrayNode required, Map<String, String> locations) {
        List<String> requiredNames = new ArrayList<>();
        required.forEach(n -> requiredNames.add(n.asText()));
        for (String name : requiredNames) {
            JsonNode prop = properties.path(name);
            if ("body".equals(locations.get(name)) && "string".equals(prop.path("type").asText())) {
                return name;
            }
        }
        return requiredNames.isEmpty() ? null : requiredNames.get(0);
    }
}
