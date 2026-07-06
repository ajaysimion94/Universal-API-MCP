package com.mcpserver.controllers;

import com.mcpserver.plugins.PluginRegistry;
import com.mcpserver.rag.retrieval.SearchPipeline;
import com.mcpserver.services.SearchService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchPipeline searchPipeline;
    private final SearchService searchService;
    private final PluginRegistry pluginRegistry;

    public SearchController(SearchPipeline searchPipeline, SearchService searchService, PluginRegistry pluginRegistry) {
        this.searchPipeline = searchPipeline;
        this.searchService = searchService;
        this.pluginRegistry = pluginRegistry;
    }

    @GetMapping
    public Map<String, Object> search(@RequestParam("q") String query,
                                      @RequestParam(value = "topK", defaultValue = "20") int topK,
                                      @RequestParam(value = "web", defaultValue = "false") boolean web) {
        if (query == null || query.isBlank()) {
            return Map.of("query", "", "results", List.of(), "mode", "empty");
        }

        if (query.startsWith("#")) {
            return Map.of(
                    "query", query,
                    "mode", "tool",
                    "tool", query.substring(1).trim(),
                    "results", List.of(),
                    "message", "No tools registered yet — tools land in Phase 3."
            );
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
}
