package com.mcpserver.rag.retrieval;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion (plan.md §3, §5.6).
 * <p>
 * Merges two ranked lists into one by summing 1/(k + rank) per item. Rank-based fusion avoids
 * mixing incomparable score scales (cosine vs ts_rank).
 */
public final class RrfFusion {

    private final int k;

    public RrfFusion(int k) {
        this.k = k;
    }

    /**
     * @param vectorRanked   candidates ordered by vector cosine similarity (best first)
     * @param lexicalRanked  candidates ordered by lexical ts_rank (best first)
     * @return fused candidates keyed by chunk id with their RRF score, best first
     */
    public List<Map.Entry<String, Float>> fuse(List<String> vectorRanked, List<String> lexicalRanked) {
        Map<String, Float> scores = new HashMap<>();
        accumulate(vectorRanked, scores);
        accumulate(lexicalRanked, scores);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .toList();
    }

    private void accumulate(List<String> ranked, Map<String, Float> scores) {
        for (int i = 0; i < ranked.size(); i++) {
            int rank = i + 1;
            scores.merge(ranked.get(i), 1f / (k + rank), Float::sum);
        }
    }
}
