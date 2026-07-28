package com.mcpserver.rag.retrieval;

import com.mcpserver.models.Chunk;
import java.util.List;

/**
 * Orchestrates the full retrieval pipeline (plan.md §5.6).
 * <p>
 * The golden-set eval harness scores any implementation of this seam (plan.md Phase 1).
 * Steps: embed query → hybrid search (vector + lexical, RRF) → rerank → context.
 */
public interface SearchPipeline {

    /**
     * @param query        plain-keyword search query
     * @param topN         number of results to return
     * @param userAclTags  ACL tags for filtering (plumbed through; pass-through until Phase 6)
     * @return ranked, cited search results
     */
    List<SearchResult> search(String query, int topN, List<String> userAclTags);

    /**
     * @param includeWeb  if true, augment results with live-web-fetched content (in-memory)
     */
    default List<SearchResult> search(String query, int topN, List<String> userAclTags, boolean includeWeb) {
        return search(query, topN, userAclTags);
    }

    /**
     * Search plus the exact generated web-query variants used for this request. Implementations
     * without a web planner retain the ordinary search contract and return no variants.
     */
    default SearchResponse searchWithMetadata(
            String query, int topN, List<String> userAclTags, boolean includeWeb) {
        return new SearchResponse(search(query, topN, userAclTags, includeWeb), List.of());
    }

    record SearchResponse(List<SearchResult> results, List<String> webQueries) {
        public SearchResponse {
            results = results == null ? List.of() : List.copyOf(results);
            webQueries = webQueries == null ? List.of() : List.copyOf(webQueries);
        }
    }

    record SearchResult(
            Chunk chunk,
            float score,
            String sourceName,
            String sourcePath,
            String sourceUrl,
            String sourceKind,
            List<String> aclTags,
            String excerpt
    ) {

        public SearchResult(Chunk chunk, float score, String sourceName, String sourcePath,
                            List<String> aclTags, String excerpt) {
            this(chunk, score, sourceName, sourcePath, null, "local", aclTags, excerpt);
        }
    }
}
