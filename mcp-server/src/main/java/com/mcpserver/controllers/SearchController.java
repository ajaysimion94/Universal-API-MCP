package com.mcpserver.controllers;

import com.mcpserver.rag.retrieval.SearchPipeline;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Search REST endpoint — the context/retrieval path (plan.md §2.1, §5.6, §5.8).
 * <p>
 * Plain keywords run RAG retrieval and return cited context. {@code #keyword} invocations
 * are the action path (Phase 3); until tools are registered, they return a structured
 * "no tools" response deterministically — no LLM in the loop. When {@code web=true},
 * results are augmented with live-web-fetched content (in-memory, not persisted).
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchPipeline searchPipeline;

    public SearchController(SearchPipeline searchPipeline) {
        this.searchPipeline = searchPipeline;
    }

    @GetMapping
    public Map<String, Object> search(@RequestParam("q") String query,
                                      @RequestParam(value = "topK", defaultValue = "20") int topK,
                                      @RequestParam(value = "web", defaultValue = "false") boolean web) {
        if (query == null || query.isBlank()) {
            return Map.of("query", "", "results", List.of(), "mode", "empty");
        }

        // #keyword action path — deterministic tool routing (Phase 3 seam).
        if (query.startsWith("#")) {
            return Map.of(
                    "query", query,
                    "mode", "tool",
                    "tool", query.substring(1).trim(),
                    "results", List.of(),
                    "message", "No tools registered yet — tools land in Phase 3."
            );
        }

        // Plain keywords → RAG context path (optionally web-augmented).
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
