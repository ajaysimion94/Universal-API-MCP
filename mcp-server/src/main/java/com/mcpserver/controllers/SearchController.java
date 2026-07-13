package com.mcpserver.controllers;

import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.ConnectionService;
import com.mcpserver.plugins.PluginRegistry;
import com.mcpserver.rag.retrieval.SearchPipeline;
import com.mcpserver.services.SearchService;
import com.mcpserver.tools.ApiTool;
import com.mcpserver.tools.ApiToolExecutor;
import com.mcpserver.tools.ApiToolService;
import com.mcpserver.tools.ToolInvocationResult;
import com.mcpserver.tools.ToolQueryParser;
import com.mcpserver.tools.ToolValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
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
    private final ObjectMapper mapper = new ObjectMapper();

    public SearchController(SearchPipeline searchPipeline, SearchService searchService,
                            PluginRegistry pluginRegistry, ApiToolService apiToolService,
                            ApiToolExecutor apiToolExecutor, ConnectionService connectionService) {
        this.searchPipeline = searchPipeline;
        this.searchService = searchService;
        this.pluginRegistry = pluginRegistry;
        this.apiToolService = apiToolService;
        this.apiToolExecutor = apiToolExecutor;
        this.connectionService = connectionService;
    }

    @GetMapping
    public Map<String, Object> search(@RequestParam("q") String query,
                                      @RequestParam(value = "topK", defaultValue = "20") int topK,
                                      @RequestParam(value = "web", defaultValue = "false") boolean web) {
        if (query == null || query.isBlank()) {
            return Map.of("query", "", "results", List.of(), "mode", "empty");
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

        List<SearchPipeline.SearchResult> results =
                searchPipeline.search(query, topK, List.of(), web);

        long localCount = results.stream().filter(r -> "local".equals(r.sourceKind())).count();
        long webCount = results.stream().filter(r -> "web".equals(r.sourceKind())).count();

        Map<String, Object> response = new HashMap<>();
        response.put("query", query);
        response.put("mode", "rag");
        response.put("web", web);
        response.put("webReady", webReady);
        if (!webReady) {
            response.put("webMessage", "Web augmentation requires the SearXNG plugin — install it on the Plugins page.");
        }
        if (!notReady.isEmpty()) {
            response.put("lexicalOnly", true);
            response.put("lexicalMessage",
                    "Semantic search is off — showing keyword matches only. Install the embedding model and vector store on the Plugins page for full search.");
        }
        response.put("results", results.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.chunk().id());
            m.put("sourceName", r.sourceName());
            m.put("sourcePath", r.sourcePath() == null ? "" : r.sourcePath());
            m.put("sourceUrl", r.sourceUrl() == null ? "" : r.sourceUrl());
            m.put("sourceKind", r.sourceKind());
            m.put("excerpt", r.excerpt());
            m.put("description", "web".equals(r.sourceKind()) ? r.excerpt() : "");
            m.put("content", "web".equals(r.sourceKind()) ? "" : r.chunk().content());
            m.put("score", r.score());
            m.put("aclTags", r.aclTags());
            m.put("position", r.chunk().position());
            return m;
        }).toList());
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

        List<ApiTool> matches = apiToolService.resolveKeyword(parsed.appSlug(), parsed.toolKeyword());
        if (matches.isEmpty()) {
            response.put("mode", "tool");
            response.put("tool", parsed.toolKeyword());
            response.put("message", parsed.toolKeyword().isBlank()
                    ? (parsed.appSlug() != null
                        ? "No tools found for app '" + parsed.appSlug() + "'. Import a Postman collection or OpenAPI spec on the Connections page."
                        : "Type a tool keyword after #, e.g. #app_create_item")
                    : "No tool matches '" + parsed.toolKeyword() + "'. Import an API spec on the Connections page, or check the keyword.");
            response.put("suggestions", suggestions(parsed.appSlug(), parsed.toolKeyword()));
            return response;
        }
        if (matches.size() > 1) {
            response.put("mode", "tool");
            response.put("tool", parsed.toolKeyword());
            response.put("message", parsed.toolKeyword().isBlank()
                    ? "Tools available for @" + parsed.appSlug() + ":"
                    : "'" + parsed.toolKeyword() + "' matches several tools — pick one:");
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
                ToolInvocationResult result = apiToolExecutor.execute(tool, connection, built.args());
                response.put("mode", "tool-result");
                response.put("result", Map.of(
                        "status", result.status(),
                        "latencyMs", result.latencyMs(),
                        "contentType", result.contentType() == null ? "" : result.contentType(),
                        "body", result.body(),
                        "truncated", result.truncated(),
                        "request", result.requestSummary()));
            } else {
                // state-changing tools never execute from a query — preview → approve (§7.2)
                response.put("mode", "tool-confirm");
                response.put("preview", apiToolExecutor.renderPreview(tool, connection, built.args()));
                response.put("args", built.args());
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

    private List<Map<String, Object>> suggestions(String appSlug, String keyword) {
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
        try {
            map.put("paramsSchema", mapper.readTree(t.paramsSchema()));
        } catch (Exception e) {
            map.put("paramsSchema", Map.of("type", "object"));
        }
        return map;
    }
}
