package com.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.connectors.Connection;
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
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiToolService(ApiToolRepository repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Upserts the parsed definitions for a connection: new requests become new tools (GET enabled,
     * writes pending), surviving tools keep their id and admin decisions (enabled/knowledge-source)
     * while spec-derived fields refresh, and tools that vanished from the spec are deleted.
     */
    public int importTools(Connection connection, List<ApiToolDefinition> definitions) {
        String appSlug = Slugifier.slug(connection.name());
        Map<String, ApiTool> existingByName = new HashMap<>();
        for (ApiTool tool : repository.findByConnectionId(connection.id())) {
            existingByName.put(tool.name(), tool);
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
        repository.deleteByConnectionId(connectionId);
        eventPublisher.publishEvent(new ToolsChangedEvent(connectionId));
    }

    public record BuiltArgs(Map<String, Object> args, List<String> missingRequired, String parseError) {
    }

    /**
     * Turns the free text after a {@code #keyword} into an argument map: an inline JSON object is
     * the full payload, inline XML flattens one level (child element → arg), anything else lands
     * in the tool's primary parameter. {@code missingRequired} lists what the inline form still
     * has to collect (required, no arg, no schema default).
     */
    public BuiltArgs buildArgs(ApiTool tool, String remainder) {
        Map<String, Object> args = new HashMap<>();
        String text = remainder == null ? "" : remainder.trim();

        if (text.startsWith("{")) {
            try {
                JsonNode node = mapper.readTree(text);
                if (!node.isObject()) {
                    return new BuiltArgs(args, List.of(), "Inline JSON must be an object of arguments");
                }
                node.properties().forEach(e -> args.put(e.getKey(),
                        mapper.convertValue(e.getValue(), Object.class)));
            } catch (Exception e) {
                return new BuiltArgs(args, List.of(), "Inline JSON didn't parse: " + e.getMessage());
            }
        } else if (text.startsWith("<")) {
            try {
                var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                var doc = factory.newDocumentBuilder()
                        .parse(new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var children = doc.getDocumentElement().getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    var child = children.item(i);
                    if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                        args.put(child.getNodeName(), child.getTextContent().trim());
                    }
                }
            } catch (Exception e) {
                return new BuiltArgs(args, List.of(), "Inline XML didn't parse: " + e.getMessage());
            }
        } else if (!text.isBlank()) {
            if (tool.primaryParam() != null) {
                args.put(tool.primaryParam(), stripQuotes(text));
            } else {
                return new BuiltArgs(args, List.of(),
                        "Tool " + tool.name() + " has no primary argument — pass inline JSON like {\"param\": \"value\"}");
            }
        }
        return new BuiltArgs(args, missingRequired(tool, args), null);
    }

    private List<String> missingRequired(ApiTool tool, Map<String, Object> args) {
        List<String> missing = new ArrayList<>();
        try {
            JsonNode schema = mapper.readTree(tool.paramsSchema());
            JsonNode required = schema.path("required");
            if (!required.isArray()) return missing;
            for (JsonNode name : required) {
                String param = name.asText();
                boolean hasDefault = schema.path("properties").path(param).has("default");
                Object value = args.get(param);
                if ((value == null || String.valueOf(value).isBlank()) && !hasDefault) {
                    missing.add(param);
                }
            }
        } catch (Exception ignored) {
            // unparseable schema — treat as nothing missing; the executor will validate again
        }
        return missing;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
