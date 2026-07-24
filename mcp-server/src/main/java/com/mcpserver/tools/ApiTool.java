package com.mcpserver.tools;

import java.time.Instant;
import java.util.UUID;

/**
 * A callable tool generated from one imported request/operation (docs/product-idea.md §8).
 * {@code name} is the full tool id {@code {app_slug}_{request_slug}} — it doubles as the MCP tool
 * name and the search-bar {@code #} keyword. JSON-typed columns (paramsSchema, paramLocations,
 * headers) are stored as raw JSON strings and interpreted by {@code ApiToolExecutor}.
 */
public record ApiTool(
        String id,
        String connectionId,
        String appSlug,
        String name,
        String requestSlug,
        String displayName,
        String description,
        String category,
        String httpMethod,
        String urlTemplate,
        String paramsSchema,
        String paramLocations,
        String headers,
        String bodyTemplate,
        String primaryParam,
        boolean enabled,
        boolean pending,
        boolean knowledgeSource,
        String extractionTemplate,
        String origin,
        Instant createdAt,
        Instant updatedAt
) {

    /** Backward compatibility constructor for direct instantiations. */
    public ApiTool(
            String id,
            String connectionId,
            String appSlug,
            String name,
            String requestSlug,
            String displayName,
            String description,
            String category,
            String httpMethod,
            String urlTemplate,
            String paramsSchema,
            String paramLocations,
            String headers,
            String bodyTemplate,
            String primaryParam,
            boolean enabled,
            boolean pending,
            boolean knowledgeSource,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, connectionId, appSlug, name, requestSlug, displayName, description,
                category, httpMethod, urlTemplate, paramsSchema, paramLocations, headers,
                bodyTemplate, primaryParam, enabled, pending, knowledgeSource, null, "IMPORTED",
                createdAt, updatedAt);
    }

    /** GET tools are callable immediately; state-changing tools start pending until approved. */
    public static ApiTool fromDefinition(String connectionId, String appSlug, ApiToolDefinition def) {
        Instant now = Instant.now();
        boolean read = def.httpMethod().equals("GET");
        return new ApiTool(
                UUID.randomUUID().toString(), connectionId, appSlug,
                appSlug + "_" + def.requestSlug(), def.requestSlug(), def.displayName(),
                def.description(), def.category(), def.httpMethod(), def.urlTemplate(),
                def.paramsSchema().toString(), toJson(def.paramLocations()), toJson(def.staticHeaders()),
                def.bodyTemplate(), def.primaryParam(),
                read, !read, false, null, "IMPORTED", now, now
        );
    }

    /** Built from the request builder rather than a spec — survives re-import untouched. */
    public static ApiTool manual(String connectionId, String appSlug, ApiToolDefinition def) {
        Instant now = Instant.now();
        boolean read = def.httpMethod().equals("GET");
        return new ApiTool(
                UUID.randomUUID().toString(), connectionId, appSlug,
                appSlug + "_" + def.requestSlug(), def.requestSlug(), def.displayName(),
                def.description(), def.category(), def.httpMethod(), def.urlTemplate(),
                def.paramsSchema().toString(), toJson(def.paramLocations()), toJson(def.staticHeaders()),
                def.bodyTemplate(), def.primaryParam(),
                read, !read, false, null, "MANUAL", now, now
        );
    }

    /** Re-import: refresh spec-derived fields, preserve identity + admin decisions. */
    public ApiTool withDefinition(ApiToolDefinition def) {
        return new ApiTool(id, connectionId, appSlug, name, requestSlug, def.displayName(),
                def.description(), def.category(), def.httpMethod(), def.urlTemplate(),
                def.paramsSchema().toString(), toJson(def.paramLocations()), toJson(def.staticHeaders()),
                def.bodyTemplate(), def.primaryParam(),
                enabled, pending, knowledgeSource, extractionTemplate, origin, createdAt, Instant.now());
    }

    /** Manual-tool edit: same identity/admin-decisions preservation, but from a fresh definition. */
    public ApiTool withManualDefinition(ApiToolDefinition def) {
        return new ApiTool(id, connectionId, appSlug, name, requestSlug, def.displayName(),
                def.description(), def.category(), def.httpMethod(), def.urlTemplate(),
                def.paramsSchema().toString(), toJson(def.paramLocations()), toJson(def.staticHeaders()),
                def.bodyTemplate(), def.primaryParam(),
                def.httpMethod().equals("GET"), !def.httpMethod().equals("GET"),
                knowledgeSource, extractionTemplate, origin, createdAt, Instant.now());
    }

    public ApiTool withEnabled(boolean newEnabled) {
        return new ApiTool(id, connectionId, appSlug, name, requestSlug, displayName, description,
                category, httpMethod, urlTemplate, paramsSchema, paramLocations, headers,
                bodyTemplate, primaryParam, newEnabled, false, knowledgeSource, extractionTemplate,
                origin, createdAt, Instant.now());
    }

    public ApiTool withKnowledgeSource(boolean newKnowledgeSource) {
        return new ApiTool(id, connectionId, appSlug, name, requestSlug, displayName, description,
                category, httpMethod, urlTemplate, paramsSchema, paramLocations, headers,
                bodyTemplate, primaryParam, enabled, pending, newKnowledgeSource, extractionTemplate,
                origin, createdAt, Instant.now());
    }

    public ApiTool withExtractionTemplate(String newExtractionTemplate) {
        return new ApiTool(id, connectionId, appSlug, name, requestSlug, displayName, description,
                category, httpMethod, urlTemplate, paramsSchema, paramLocations, headers,
                bodyTemplate, primaryParam, enabled, pending, knowledgeSource, newExtractionTemplate,
                origin, createdAt, Instant.now());
    }

    public boolean isRead() {
        return httpMethod.equals("GET");
    }

    public boolean isManual() {
        return "MANUAL".equals(origin);
    }

    private static String toJson(java.util.Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
