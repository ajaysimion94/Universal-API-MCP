package com.mcpserver.rag.reranker;

import com.mcpserver.models.Chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Pass-through reranker (plan.md §5.6 — reranking is a quality lift, staged).
 * <p>
 * Preserves the incoming RRF order with descending synthetic scores so the interface contract holds.
 * Swap for {@code OnnxCrossEncoderReranker} (bge-reranker) without changing the pipeline.
 */
public class PassThroughReranker implements Reranker {

    @Override
    public List<ScoredChunk> rerank(String query, List<Chunk> candidates) {
        List<ScoredChunk> out = new ArrayList<>(candidates.size());
        float score = candidates.size();
        for (Chunk c : candidates) {
            out.add(new ScoredChunk(c, score--));
        }
        return out;
    }
}
