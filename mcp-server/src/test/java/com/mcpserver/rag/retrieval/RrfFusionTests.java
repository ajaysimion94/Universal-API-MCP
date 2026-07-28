package com.mcpserver.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
