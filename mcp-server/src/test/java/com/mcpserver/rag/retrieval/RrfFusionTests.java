package com.mcpserver.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTests {

    @Test
    void fusesAgreementAheadOfSingleLegHits() {
        var ranked = new RrfFusion(60).fuse(
                List.of("vector-only", "both"),
                List.of("both", "lexical-only"));

        assertThat(ranked).extracting(entry -> entry.getKey())
                .containsExactly("both", "vector-only", "lexical-only");
    }

    @Test
    void equalScoresUseStableChunkIdTieBreaker() {
        var fusion = new RrfFusion(60);

        assertThat(fusion.fuse(List.of("zeta"), List.of("alpha")))
                .extracting(entry -> entry.getKey())
                .containsExactly("alpha", "zeta");
    }

    /**
     * The learned policy's baseline arm is (1, 1). If that arm were not float-for-float identical to
     * the unweighted path, enabling the ranking policy would silently perturb every existing
     * ranking — including the golden-set gate — before it had learned anything.
     */
    @Test
    void unitWeightsAreIdenticalToUnweightedFusion() {
        var fusion = new RrfFusion(60);
        List<String> vector = List.of("vector-only", "both", "third");
        List<String> lexical = List.of("both", "lexical-only", "third");

        assertThat(fusion.fuse(vector, lexical))
                .isEqualTo(fusion.fuse(vector, lexical, 1f, 1f));
    }

    @Test
    void raisingVectorWeightLiftsAVectorOnlyHitAboveALexicalOnlyHit() {
        var fusion = new RrfFusion(60);
        List<String> vector = List.of("vector-only");
        List<String> lexical = List.of("lexical-only");

        // Equal weights: both sit at rank 1 in their own leg, so the id tie-breaker decides.
        assertThat(fusion.fuse(vector, lexical, 1f, 1f)).extracting(Map.Entry::getKey)
                .containsExactly("lexical-only", "vector-only");

        assertThat(fusion.fuse(vector, lexical, 1.6f, 0.4f)).extracting(Map.Entry::getKey)
                .containsExactly("vector-only", "lexical-only");
    }

    @Test
    void zeroWeightDegeneratesToTheOtherLegsOrdering() {
        var ranked = new RrfFusion(60).fuse(
                List.of("vector-first", "vector-second"),
                List.of("lexical-first", "lexical-second"),
                0f, 2f);

        assertThat(ranked).extracting(Map.Entry::getKey)
                .startsWith("lexical-first", "lexical-second");
        assertThat(ranked).filteredOn(entry -> entry.getKey().startsWith("vector"))
                .allMatch(entry -> entry.getValue() == 0f);
    }
}
