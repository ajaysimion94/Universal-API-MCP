package com.mcpserver.rag.web;

import java.util.List;

/**
 * Fetches relevant web results for a query (plan.md §5.8 universal search — live-web
 * augmentation when the "Web" toggle is on).
 * <p>
 * Provider-swappable seam. The default impl queries a local SearXNG instance (open-source,
 * native process) and returns the title + description snippet SearXNG already provides per
 * result — no page fetching or text extraction. Web results are not persisted.
 */
public interface WebFetcher {

    /**
     * @param query  the search query
     * @param topN   max number of web results to return
     * @return web results, best first; empty list if the web source is unavailable
     */
    List<WebResult> fetch(String query, int topN);

    record WebResult(
            String url,
            String title,
            String description,
            String engine
    ) {}
}
