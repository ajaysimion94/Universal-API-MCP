package com.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcpserver.connectors.Connection;
import com.mcpserver.workflow.ParameterExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for imported API tools: upsert on (re-)import, enable/disable lifecycle,
 * knowledge-source flagging, and name/keyword lookup for the search grammar and MCP bridge.
 */
@Service
public class ApiToolService {

    private static final Logger log = LoggerFactory.getLogger(ApiToolService.class);

    private final ApiToolRepository repository;
    private final ToolGroupRepository toolGroupRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ParameterExtractor parameterExtractor;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiToolService(ApiToolRepository repository, ToolGroupRepository toolGroupRepository,
                          ApplicationEventPublisher eventPublisher, ParameterExtractor parameterExtractor) {
        this.repository = repository;
        this.toolGroupRepository = toolGroupRepository;
        this.eventPublisher = eventPublisher;
        this.parameterExtractor = parameterExtractor;
    }

    /**
     * Upserts the parsed definitions for a connection: new requests become new tools (GET enabled,
     * writes pending), surviving tools keep their id and admin decisions (enabled/knowledge-source)
     * while spec-derived fields refresh, and tools that vanished from the spec are deleted.
     */
    public int importTools(Connection connection, List<ApiToolDefinition> definitions) {
        String appSlug = Slugifier.slug(connection.name());
        // Manual (from-scratch) tools are never spec-derived — re-import must never refresh or
        // delete them, so they're excluded from the reconciliation set entirely.
        Map<String, ApiTool> existingByName = new HashMap<>();
        for (ApiTool tool : repository.findByConnectionId(connection.id())) {
            if (!tool.isManual()) existingByName.put(tool.name(), tool);
        }

        int imported = 0;
        for (ApiToolDefinition def : dedupeSlugs(definitions)) {
            String name = appSlug + "_" + def.requestSlug();
            ApiTool existing = existingByName.remove(name);
            repository.save(existing != null
                    ? existing.withDefinition(def)
                    : ApiTool.fromDefinition(connection.id(), appSlug, def));
            imported++;
        }
        for (ApiTool vanished : existingByName.values()) {
            repository.deleteById(vanished.id());
            toolGroupRepository.deleteMembersForTool(vanished.id());
        }
        log.info("Imported {} tools for connection {} ({} removed)",
                imported, connection.id(), existingByName.size());
        eventPublisher.publishEvent(new ToolsChangedEvent(connection.id()));
        return imported;
    }

    /** Two "Get user" requests in different folders must not collide on the same slug. */
    private static List<ApiToolDefinition> dedupeSlugs(List<ApiToolDefinition> definitions) {
        Map<String, Integer> seen = new HashMap<>();
        List<ApiToolDefinition> out = new ArrayList<>(definitions.size());
        for (ApiToolDefinition def : definitions) {
            int n = seen.merge(def.requestSlug(), 1, Integer::sum);
            out.add(n == 1 ? def : new ApiToolDefinition(
                    def.displayName(), def.requestSlug() + "_" + n, def.description(),
                    def.category(), def.httpMethod(), def.urlTemplate(), def.paramsSchema(),
                    def.paramLocations(), def.staticHeaders(), def.bodyTemplate(), def.primaryParam()));
        }
        return out;
    }

    public ApiTool findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + id));
    }

    public List<ApiTool> findByConnectionId(String connectionId) {
        return repository.findByConnectionId(connectionId);
    }

    public List<ApiTool> findAllEnabled() {
        return repository.findAllEnabled();
    }

    public List<ApiTool> findKnowledgeSources(String connectionId) {
        return repository.findKnowledgeSources(connectionId);
    }

    /** Substring match on tool name / display name / app slug — backs the search-bar autocomplete. */
    public List<ApiTool> search(String query, String connectionId) {
        List<ApiTool> all = connectionId != null && !connectionId.isBlank()
                ? repository.findByConnectionId(connectionId)
                : repository.findAll();
        if (query == null || query.isBlank()) return all;
        String q = query.toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(t -> t.name().contains(q)
                        || t.displayName().toLowerCase(Locale.ROOT).contains(q)
                        || t.appSlug().contains(q))
                .toList();
    }

    /** Exact match on the full tool id, e.g. "petstore_get_pet_by_id". */
    public Optional<ApiTool> findByFullName(String name) {
        List<ApiTool> matches = repository.findByName(name);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    /**
     * Resolves a search-bar keyword: exact full-name match first; then, when {@code appSlug} is
     * given, exact request-slug within that app; then prefix matches (unique prefix resolves,
     * ambiguous prefix returns all candidates for a suggestion list).
     */
    public List<ApiTool> resolveKeyword(String appSlug, String keyword) {
        String kw = keyword.toLowerCase(Locale.ROOT);
        List<ApiTool> pool = repository.findAll().stream()
                .filter(t -> appSlug == null || t.appSlug().equals(appSlug))
                .toList();

        List<ApiTool> exact = pool.stream()
                .filter(t -> t.name().equals(kw) || (appSlug != null && t.requestSlug().equals(kw)))
                .toList();
        if (!exact.isEmpty()) return exact;

        return pool.stream()
                .filter(t -> t.name().startsWith(kw)
                        || (appSlug != null && t.requestSlug().startsWith(kw)))
                .toList();
    }

    public ApiTool setEnabled(String id, boolean enabled) {
        ApiTool tool = findById(id);
        ApiTool updated = tool.withEnabled(enabled);
        if (!enabled && tool.knowledgeSource()) {
            updated = updated.withKnowledgeSource(false);
        }
        repository.save(updated);
        eventPublisher.publishEvent(new ToolsChangedEvent(tool.connectionId()));
        return updated;
    }

    /**
     * Knowledge-source flag: only enabled GET tools whose required parameters all carry defaults
     * can be auto-invoked on a schedule (there is nobody to fill a form at poll time).
     */
    public ApiTool setKnowledgeSource(String id, boolean flag) {
        ApiTool tool = findById(id);
        if (flag) {
            if (!tool.isRead()) {
                throw new IllegalArgumentException(
                        "Only GET tools can be knowledge sources: " + tool.name());
            }
            if (!tool.enabled()) {
                throw new IllegalArgumentException(
                        "Enable the tool before marking it as a knowledge source: " + tool.name());
            }
            String blocker = firstUnsatisfiableRequiredParam(tool);
            if (blocker != null) {
                throw new IllegalArgumentException("Tool " + tool.name() + " requires parameter '"
                        + blocker + "' with no default — it can't be invoked automatically");
            }
        }
        ApiTool updated = tool.withKnowledgeSource(flag);
        repository.save(updated);
        return updated;
    }

    /** First required schema property without a default value, or null when auto-invokable. */
    public String firstUnsatisfiableRequiredParam(ApiTool tool) {
        try {
            JsonNode schema = mapper.readTree(tool.paramsSchema());
            JsonNode required = schema.path("required");
            if (!required.isArray()) return null;
            for (JsonNode name : required) {
                if (!schema.path("properties").path(name.asText()).has("default")) {
                    return name.asText();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public void deleteByConnectionId(String connectionId) {
        for (ApiTool tool : repository.findByConnectionId(connectionId)) {
            toolGroupRepository.deleteMembersForTool(tool.id());
        }
        repository.deleteByConnectionId(connectionId);
        eventPublisher.publishEvent(new ToolsChangedEvent(connectionId));
    }

    public record BuiltArgs(Map<String, Object> args, List<String> missingRequired, String parseError) {
    }

    /**
     * Turns the free text after a {@code #keyword} into an argument map: delegates to
     * ParameterExtractor to parse using templates or standard JSON/XML fallbacks.
     */
    public BuiltArgs buildArgs(ApiTool tool, String remainder) {
        ParameterExtractor.ExtractedArgs ext = parameterExtractor.extract(tool, remainder);
        return new BuiltArgs(ext.args(), ext.missingRequired(), ext.parseError());
    }

    // --- Manual (from-scratch) tools — the request-builder's "New request" / "Save" -----------

    /** One query or header field on a manually-built request. */
    public record ManualParam(String name, String in, boolean required, String defaultValue, String description) {
    }

    public record ManualToolInput(
            String displayName,
            String method,
            String path,
            String category,
            String description,
            List<ManualParam> params,
            String bodyTemplate
    ) {
    }

    /** Creates a new manual tool under {@code connectionId}. GET is enabled immediately; writes start pending. */
    public ApiTool createManual(String connectionId, Connection connection, ManualToolInput input) {
        String appSlug = Slugifier.slug(connection.name());
        ApiToolDefinition def = buildManualDefinition(input, dedupeManualSlug(connectionId, Slugifier.slug(input.displayName())));
        ApiTool tool = ApiTool.manual(connectionId, appSlug, def);
        repository.save(tool);
        eventPublisher.publishEvent(new ToolsChangedEvent(connectionId));
        return tool;
    }

    /** Updates an existing manual tool's shape. Rejects imported tools — they're spec-managed. */
    public ApiTool updateManual(String id, ManualToolInput input) {
        ApiTool existing = findById(id);
        if (!existing.isManual()) {
            throw new IllegalStateException("Tool " + existing.name()
                    + " is managed by its connection's spec — re-import or delete the connection instead");
        }
        ApiToolDefinition def = buildManualDefinition(input, existing.requestSlug());
        ApiTool updated = existing.withManualDefinition(def);
        repository.save(updated);
        eventPublisher.publishEvent(new ToolsChangedEvent(existing.connectionId()));
        return updated;
    }

    /** Deletes a manual tool. Rejects imported tools — they're spec-managed. */
    public void deleteManual(String id) {
        ApiTool existing = findById(id);
        if (!existing.isManual()) {
            throw new IllegalStateException("Tool " + existing.name()
                    + " is managed by its connection's spec — re-import or delete the connection instead");
        }
        toolGroupRepository.deleteMembersForTool(id);
        repository.deleteById(id);
        eventPublisher.publishEvent(new ToolsChangedEvent(existing.connectionId()));
    }

    private String dedupeManualSlug(String connectionId, String baseSlug) {
        var existingSlugs = repository.findByConnectionId(connectionId).stream()
                .map(ApiTool::requestSlug).collect(java.util.stream.Collectors.toSet());
        if (!existingSlugs.contains(baseSlug)) return baseSlug;
        int n = 2;
        while (existingSlugs.contains(baseSlug + "_" + n)) n++;
        return baseSlug + "_" + n;
    }

    /** Builds the same {@link ApiToolDefinition} shape the Postman/OpenAPI parsers produce, by hand. */
    private ApiToolDefinition buildManualDefinition(ManualToolInput input, String requestSlug) {
        String method = input.method() == null ? "GET" : input.method().toUpperCase(Locale.ROOT);
        String path = input.path() == null || input.path().isBlank() ? "/" : input.path();
        if (!path.startsWith("/")) path = "/" + path;

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        Map<String, String> locations = new HashMap<>();

        // Path params are inferred from {placeholders} in the path, same convention as imports.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\w+)}").matcher(path);
        while (m.find()) {
            String name = m.group(1);
            ObjectNode prop = properties.putObject(name);
            prop.put("type", "string");
            required.add(name);
            locations.put(name, "path");
        }

        List<ManualParam> params = input.params() == null ? List.of() : input.params();
        for (ManualParam p : params) {
            if (p.name() == null || p.name().isBlank()) continue;
            // A name colliding with a {path} placeholder would silently overwrite its "path"
            // location below — the placeholder already declared this param, so skip the duplicate.
            if (locations.containsKey(p.name())) continue;
            String in = "header".equals(p.in()) ? "header" : "query";
            ObjectNode prop = properties.putObject(p.name());
            prop.put("type", "string");
            if (p.description() != null && !p.description().isBlank()) prop.put("description", p.description());
            if (p.defaultValue() != null && !p.defaultValue().isBlank()) prop.put("default", p.defaultValue());
            if (p.required()) required.add(p.name());
            locations.put(p.name(), in);
        }

        String bodyTemplate = input.bodyTemplate() == null || input.bodyTemplate().isBlank()
                ? null : input.bodyTemplate();
        if (bodyTemplate != null) {
            try {
                JsonNode parsed = mapper.readTree(bodyTemplate);
                if (parsed.isObject()) {
                    parsed.properties().forEach(e -> {
                        if (!properties.has(e.getKey())) {
                            ObjectNode prop = properties.putObject(e.getKey());
                            prop.put("type", jsonType(e.getValue()));
                        }
                        locations.put(e.getKey(), "body");
                    });
                }
            } catch (Exception ignored) {
                // not JSON — sent as-is via the body template, no body fields exposed as params
            }
        }

        String displayName = input.displayName() == null || input.displayName().isBlank()
                ? method + " " + path : input.displayName();
        String category = input.category() == null || input.category().isBlank() ? "Manual" : input.category();

        return new ApiToolDefinition(displayName, requestSlug, input.description(), category, method, path,
                schema, locations, Map.of(), bodyTemplate, null);
    }

    private static String jsonType(JsonNode node) {
        if (node.isTextual()) return "string";
        if (node.isBoolean()) return "boolean";
        if (node.isIntegralNumber()) return "integer";
        if (node.isNumber()) return "number";
        if (node.isArray()) return "array";
        if (node.isObject()) return "object";
        return "string";
    }
}
