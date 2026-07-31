package com.mcpserver.controllers;

import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.ConnectionService;
import com.mcpserver.plugins.PluginRegistry;
import com.mcpserver.rag.retrieval.SearchPipeline;
import com.mcpserver.services.SearchService;
import com.mcpserver.audit.AuditService;
import com.mcpserver.tools.ApiTool;
import com.mcpserver.tools.ApiToolExecutor;
import com.mcpserver.tools.ApiToolService;
import com.mcpserver.tools.ToolGroup;
import com.mcpserver.tools.ToolGroupService;
import com.mcpserver.tools.ToolInvocationResult;
import com.mcpserver.tools.ToolQueryParser;
import com.mcpserver.tools.ToolValidationException;
import com.mcpserver.workflow.WorkflowEngine;
import com.mcpserver.workflow.WorkflowExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchPipeline searchPipeline;
    private final SearchService searchService;
    private final PluginRegistry pluginRegistry;
    private final ApiToolService apiToolService;
    private final ApiToolExecutor apiToolExecutor;
    private final ConnectionService connectionService;
    private final ToolGroupService toolGroupService;
    private final WorkflowEngine workflowEngine;
    private final AuditService auditService;
    private final ObjectMapper mapper = new ObjectMapper();

    public SearchController(SearchPipeline searchPipeline, SearchService searchService,
                            PluginRegistry pluginRegistry, ApiToolService apiToolService,
                            ApiToolExecutor apiToolExecutor, ConnectionService connectionService,
                            ToolGroupService toolGroupService, WorkflowEngine workflowEngine,
                            AuditService auditService) {
        this.searchPipeline = searchPipeline;
        this.searchService = searchService;
        this.pluginRegistry = pluginRegistry;
        this.apiToolService = apiToolService;
        this.apiToolExecutor = apiToolExecutor;
        this.connectionService = connectionService;
        this.toolGroupService = toolGroupService;
        this.workflowEngine = workflowEngine;
        this.auditService = auditService;
    }

    @GetMapping
    public Map<String, Object> search(@RequestParam("q") String query,
                                      @RequestParam(value = "topK", defaultValue = "20") int topK,
                                      @RequestParam(value = "web", defaultValue = "false") boolean web) {
        if (query == null || query.isBlank()) {
            return Map.of("query", "", "results", List.of(), "mode", "empty");
        }
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("topK must be between 1 and 100");
        }

        Optional<ToolQueryParser.ParsedToolQuery> toolQuery = ToolQueryParser.parse(query);
        if (toolQuery.isPresent()) {
            return handleToolQuery(query, toolQuery.get());
        }

        // Missing plugins only block search entirely when nothing has been indexed yet —
        // lexically-indexed chunks remain searchable without the embedding/vector plugins.
        List<String> notReady = searchService.getNotReadyPlugins();
        if (!notReady.isEmpty() && !searchService.hasIndexedChunks()) {
            Map<String, Object> response = new HashMap<>();
            response.put("query", query);
            response.put("mode", "notReady");
            response.put("requiresSetup", notReady);
            response.put("pluginStatus", pluginRegistry.getAll().stream()
                    .filter(p -> notReady.contains(p.id()))
                    .map(p -> Map.of(
                            "id", p.id(),
                            "name", p.name(),
                            "status", p.status().name(),
                            "ready", p.isReady(),
                            "health", p.health()
                    ))
                    .toList());
            response.put("message", "Search requires the embedding model and vector store to be active. Open Plugins and check the listed plugin health.");
            response.put("results", List.of());
            return response;
        }

        boolean webReady = !web || pluginRegistry.isReady("searxng");

        SearchPipeline.SearchResponse searchResponse =
                searchPipeline.searchWithMetadata(query, topK, List.of(), web);
        List<SearchPipeline.SearchResult> results = searchResponse.results();

        long localCount = results.stream().filter(r -> "local".equals(r.sourceKind())).count();
        long webCount = results.stream().filter(r -> "web".equals(r.sourceKind())).count();

        Map<String, Object> response = new HashMap<>();
        response.put("query", query);
        response.put("mode", "rag");
        response.put("web", web);
        response.put("webReady", webReady);
        if (web && webReady) {
            response.put("webQueries", searchResponse.webQueries());
        }
        if (!webReady) {
            response.put("webMessage", "Web augmentation requires the SearXNG plugin — install it on the Plugins page.");
        }
        if (!notReady.isEmpty()) {
            response.put("lexicalOnly", true);
            response.put("lexicalMessage",
                    "Semantic search is off — showing keyword matches only. Install the embedding model and vector store on the Plugins page for full search.");
        }
        response.put("results", results.stream().map(SearchResultMapper::toJson).toList());
        response.put("total", results.size());
        response.put("localCount", localCount);
        response.put("webCount", webCount);
        return response;
    }

    /**
     * The {@code @app #tool} branch (§5.8): resolves the keyword deterministically — no
     * classifier, no LLM. Reads execute inline; writes return a preview for the UI's confirm
     * step; missing args return the tool's schema so the UI renders the inline form.
     */
    private Map<String, Object> handleToolQuery(String query, ToolQueryParser.ParsedToolQuery parsed) {
        Map<String, Object> response = new HashMap<>();
        response.put("query", query);
        response.put("results", List.of());

        // A slug with no app behind it may name a custom group. Apps keep priority on collision:
        // an app that actually has tools always wins its slug, so existing apps never regress.
        ToolGroup group = null;
        if (parsed.appSlug() != null) {
            boolean appHasTools = apiToolService.search(null, null).stream()
                    .anyMatch(t -> t.appSlug().equals(parsed.appSlug()));
            if (!appHasTools) {
                group = toolGroupService.findBySlug(parsed.appSlug()).orElse(null);
            }
        }

        List<ApiTool> matches = group != null
                ? toolGroupService.resolveInGroup(group.slug(), parsed.toolKeyword())
                : apiToolService.resolveKeyword(parsed.appSlug(), parsed.toolKeyword());
        if (matches.isEmpty()) {
            response.put("mode", "tool");
            response.put("tool", parsed.toolKeyword());
            response.put("message", noMatchMessage(parsed, group));
            response.put("suggestions", suggestions(parsed.appSlug(), parsed.toolKeyword(), group));
            return response;
        }
        // A bare "@app" is a browse, not an ambiguous invocation: answer it with the app's whole
        // catalogue rather than the first eight guesses, so the page can table it.
        if (parsed.toolKeyword().isBlank()) {
            response.put("mode", "tool-catalog");
            response.put("scope", group != null ? group.slug() : parsed.appSlug());
            response.put("scopeName", group != null ? group.name() : appName(parsed.appSlug()));
            response.put("scopeKind", group != null ? "group" : "app");
            response.put("tools", matches.stream().map(this::toolSummary).toList());
            return response;
        }
        if (matches.size() > 1) {
            response.put("mode", "tool");
            response.put("tool", parsed.toolKeyword());
            response.put("message", "'" + parsed.toolKeyword() + "' matches several tools — pick one:");
            response.put("suggestions", matches.stream().limit(8).map(this::toolSummary).toList());
            return response;
        }

        ApiTool tool = matches.get(0);
        response.put("toolInfo", toolSummary(tool));
        if (!tool.enabled()) {
            response.put("mode", "tool");
            response.put("tool", tool.name());
            response.put("message", tool.pending()
                    ? "Tool " + tool.name() + " is pending approval — enable it on the Connections page."
                    : "Tool " + tool.name() + " is disabled — enable it on the Connections page.");
            return response;
        }

        ApiToolService.BuiltArgs built = apiToolService.buildArgs(tool, parsed.remainder());
        if (built.parseError() != null) {
            response.put("mode", "tool-form");
            response.put("prefill", built.args());
            response.put("error", built.parseError());
            return response;
        }
        if (!built.missingRequired().isEmpty()) {
            response.put("mode", "tool-form");
            response.put("prefill", built.args());
            response.put("missingRequired", built.missingRequired());
            return response;
        }

        Connection connection = connectionService.findById(tool.connectionId());
        try {
            if (tool.isRead()) {
                auditService.logToolInvoked(tool.id(), tool.name(), null, "web-user", built.args());
                ToolInvocationResult result = apiToolExecutor.execute(tool, connection, built.args());
                auditService.logToolExecuted(tool.id(), tool.name(), null, "web-user",
                        "HTTP " + result.status());
                response.put("mode", "tool-result");
                response.put("result", Map.of(
                        "status", result.status(),
                        "latencyMs", result.latencyMs(),
                        "contentType", result.contentType() == null ? "" : result.contentType(),
                        "body", result.body(),
                        "truncated", result.truncated(),
                        "request", result.requestSummary(),
                        "headers", result.headers()));
            } else {
                // State-changing tools → workflow engine → preview + confirmation token (§7.2)
                auditService.logToolInvoked(tool.id(), tool.name(), null, "web-user", built.args());
                WorkflowExecution execution = workflowEngine.initiateWriteTool(
                        tool, connection, built.args(), "web-user");
                response.put("mode", "tool-confirm");
                try {
                    response.put("preview", mapper.readValue(execution.previewPayload(), Map.class));
                } catch (Exception e) {
                    response.put("preview", Map.of());
                }
                response.put("args", built.args());
                response.put("confirmationToken", execution.confirmationToken());
                response.put("tokenExpiresAt", execution.tokenExpiresAt().toString());
                response.put("workflowId", execution.id());
            }
        } catch (ToolValidationException e) {
            response.put("mode", "tool-form");
            response.put("prefill", built.args());
            response.put("violations", e.violations().stream()
                    .map(v -> Map.of("param", v.param(), "expected", v.expected(), "message", v.message()))
                    .toList());
        } catch (Exception e) {
            response.put("mode", "tool");
            response.put("tool", tool.name());
            response.put("message", "Tool execution failed: " + e.getMessage());
        }
        return response;
    }

    private String noMatchMessage(ToolQueryParser.ParsedToolQuery parsed, ToolGroup group) {
        if (parsed.toolKeyword().isBlank()) {
            if (group != null) {
                return "No tools in group '" + group.slug() + "' yet — add apps or endpoints to it on the Apps page.";
            }
            return parsed.appSlug() != null
                    ? "No tools found for app '" + parsed.appSlug() + "'. Import a Postman collection or OpenAPI spec on the Connections page."
                    : "Type a tool keyword after #, e.g. #app_create_item";
        }
        return group != null
                ? "No tool matches '" + parsed.toolKeyword() + "' in group '" + group.slug() + "' — check the keyword."
                : "No tool matches '" + parsed.toolKeyword() + "'. Import an API spec on the Connections page, or check the keyword.";
    }

    private List<Map<String, Object>> suggestions(String appSlug, String keyword, ToolGroup group) {
        if (group != null) {
            // group scope: suggest from the group's pool instead of the global one
            List<ApiTool> groupPool = toolGroupService.toolsInGroup(group.id());
            String kw = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT);
            List<Map<String, Object>> matched = groupPool.stream()
                    .filter(t -> kw.isBlank()
                            || t.name().contains(kw)
                            || t.displayName().toLowerCase(Locale.ROOT).contains(kw))
                    .filter(ApiTool::enabled)
                    .limit(8)
                    .map(this::toolSummary)
                    .toList();
            if (!matched.isEmpty()) return matched;
            // nothing matched the keyword — show what IS available in the group
            return groupPool.stream()
                    .filter(ApiTool::enabled)
                    .limit(8)
                    .map(this::toolSummary)
                    .toList();
        }
        List<ApiTool> pool = keyword == null || keyword.isBlank()
                ? apiToolService.search(null, null)
                : apiToolService.search(keyword, null);
        List<Map<String, Object>> matched = pool.stream()
                .filter(t -> appSlug == null || t.appSlug().equals(appSlug))
                .filter(ApiTool::enabled)
                .limit(8)
                .map(this::toolSummary)
                .toList();
        if (!matched.isEmpty()) return matched;
        // nothing matched the keyword — show what IS available instead of an empty list
        return apiToolService.search(null, null).stream()
                .filter(t -> appSlug == null || t.appSlug().equals(appSlug))
                .filter(ApiTool::enabled)
                .limit(8)
                .map(this::toolSummary)
                .toList();
    }

    /** The connection's display name for a slug, falling back to the slug when nothing matches. */
    private String appName(String appSlug) {
        if (appSlug == null) return null;
        return apiToolService.search(null, null).stream()
                .filter(tool -> tool.appSlug().equals(appSlug))
                .findFirst()
                .map(tool -> {
                    try {
                        return connectionService.findById(tool.connectionId()).name();
                    } catch (RuntimeException exception) {
                        return appSlug;
                    }
                })
                .orElse(appSlug);
    }

    private Map<String, Object> toolSummary(ApiTool t) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", t.id());
        map.put("name", t.name());
        map.put("appSlug", t.appSlug());
        map.put("displayName", t.displayName());
        map.put("description", t.description() == null ? "" : t.description());
        map.put("method", t.httpMethod());
        map.put("enabled", t.enabled());
        map.put("pending", t.pending());
        map.put("primaryParam", t.primaryParam());
        map.put("urlTemplate", t.urlTemplate());
        map.put("bodyTemplate", t.bodyTemplate() == null ? "" : t.bodyTemplate());
        try {
            map.put("paramsSchema", mapper.readTree(t.paramsSchema()));
        } catch (Exception e) {
            map.put("paramsSchema", Map.of("type", "object"));
        }
        try {
            map.put("paramLocations", mapper.readTree(t.paramLocations()));
        } catch (Exception e) {
            map.put("paramLocations", Map.of());
        }
        return map;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }
}
