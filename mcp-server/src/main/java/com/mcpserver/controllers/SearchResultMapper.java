package com.mcpserver.controllers;

import com.mcpserver.rag.retrieval.SearchPipeline;

import java.util.HashMap;
import java.util.Map;

/**
 * The single JSON shape every {@link SearchPipeline.SearchResult} is serialized to —
 * used by both the search endpoint and the chat endpoint's {@code sources} event, so the
 * Web UI renders either with the same components.
 */
final class SearchResultMapper {

    private SearchResultMapper() {}

    static Map<String, Object> toJson(SearchPipeline.SearchResult r) {
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
    }
}
