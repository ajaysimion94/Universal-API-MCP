package com.mcpserver.rag.retrieval;

import java.util.HashMap;
import java.util.Comparator;
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
     * Unweighted fusion — identical, float-for-float, to {@link #fuse(List, List, float, float)}
     * at weights (1, 1).
     *
     * @param vectorRanked   candidates ordered by vector cosine similarity (best first)
     * @param lexicalRanked  candidates ordered by lexical ts_rank (best first)
     * @return fused candidates keyed by chunk id with their RRF score, best first
     */
    public List<Map.Entry<String, Float>> fuse(List<String> vectorRanked, List<String> lexicalRanked) {
        return fuse(vectorRanked, lexicalRanked, 1f, 1f);
    }

    /**
     * Weighted fusion. The per-leg weights are what the ranking policy learns
     * (com.mcpserver.learning): raising one leg's weight lifts every id it ranked, so a query class
     * where lexical matching wins can be served by a different blend than one where it loses.
     *
     * @param vectorWeight   multiplier on the vector leg's reciprocal-rank contribution
     * @param lexicalWeight  multiplier on the lexical leg's contribution
     */
    public List<Map.Entry<String, Float>> fuse(List<String> vectorRanked, List<String> lexicalRanked,
                                               float vectorWeight, float lexicalWeight) {
        Map<String, Float> scores = new HashMap<>();
        accumulate(vectorRanked, scores, vectorWeight);
        accumulate(lexicalRanked, scores, lexicalWeight);
        return scores.entrySet().stream()
                // Equal RRF scores are common (for example, an item at rank 1 in only
                // one leg). HashMap iteration order is not a ranking contract, so use
                // the stable chunk id as the final tie-breaker.
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey, Comparator.naturalOrder()))
                .toList();
    }

    private void accumulate(List<String> ranked, Map<String, Float> scores, float weight) {
        for (int i = 0; i < ranked.size(); i++) {
            int rank = i + 1;
            scores.merge(ranked.get(i), weight / (k + rank), Float::sum);
        }
    }
}
