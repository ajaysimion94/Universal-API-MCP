package com.mcpserver.rag.reranker;

import com.mcpserver.models.Chunk;
import java.util.List;

/**
 * Re-scores the top-N hybrid candidates for final ordering (plan.md §5.6 step 3).
 * <p>
 * Default impl is pass-through (keeps RRF order). bge-reranker cross-encoder (ONNX) wires in here.
 */
public interface Reranker {

    /**
     * @param query     the original search query
     * @param candidates ordered hybrid candidates (best first)
     * @return re-scored candidates (best first)
     */
    List<ScoredChunk> rerank(String query, List<Chunk> candidates);

    record ScoredChunk(Chunk chunk, float score) {}
}
