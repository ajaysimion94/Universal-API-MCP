package com.mcpserver.controllers;

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

    public SearchController(SearchPipeline searchPipeline, SearchService searchService) {
        this.searchPipeline = searchPipeline;
        this.searchService = searchService;
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

        List<String> notReady = searchService.getNotReadyPlugins();
        if (!notReady.isEmpty()) {
            Map<String, Object> response = new HashMap<>();
            response.put("query", query);
            response.put("mode", "notReady");
            response.put("requiresSetup", notReady);
            response.put("message", "Search requires plugins to be installed. Visit the Plugins page to set up the embedding model and vector store.");
            response.put("results", List.of());
            return response;
        }

        List<SearchPipeline.SearchResult> results =
                searchPipeline.search(query, topK, List.of(), web);

        long localCount = results.stream().filter(r -> "local".equals(r.sourceKind())).count();
        long webCount = results.stream().filter(r -> "web".equals(r.sourceKind())).count();

        Map<String, Object> response = new HashMap<>();
        response.put("query", query);
        response.put("mode", "rag");
        response.put("web", web);
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
