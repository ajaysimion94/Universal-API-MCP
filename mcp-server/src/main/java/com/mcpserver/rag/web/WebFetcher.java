package com.mcpserver.rag.web;

import java.util.List;
import java.time.Instant;

/**
 * Fetches relevant web results for a query (plan.md §5.8 universal search — live-web
 * augmentation when the "Web" toggle is on).
 * <p>
 * Provider-swappable seam. The default impl queries a local SearXNG instance (open-source,
 * native process). Page retrieval and relevance ranking are deliberately separate so provider
 * order is only one input to the final result. Web results are not persisted.
 */
public interface WebFetcher {

    /**
     * @param queries   contextual query variants to execute
     * @param perQuery  maximum results to retain from each variant
     * @return provider results carrying their original query/rank; empty if unavailable
     */
    List<WebResult> fetch(List<String> queries, int perQuery);

    record WebResult(
            String url,
            String title,
            String description,
            String engine,
            String query,
            int providerRank,
            double providerScore,
            Instant publishedAt
    ) {}
}
