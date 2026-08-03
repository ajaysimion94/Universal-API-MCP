package com.mcpserver.learning;

import com.mcpserver.learning.LearningModel.Feedback;
import com.mcpserver.learning.LearningModel.Impression;
import com.mcpserver.learning.LearningModel.ServedResult;
import com.mcpserver.learning.LearningModel.Signal;
import com.mcpserver.rag.retrieval.TextSignals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FeedbackMemoryTests {

    @Autowired
    FeedbackMemory memory;

    @Autowired
    FeedbackMemoryRepository repository;

    @BeforeEach
    void clean() {
        memory.reset();
    }

    private static Impression impression(String query, String... chunkIds) {
        List<ServedResult> results = new java.util.ArrayList<>();
        for (int i = 0; i < chunkIds.length; i++) {
            results.add(new ServedResult(chunkIds[i], i + 1, 0.5f));
        }
        return new Impression(UUID.randomUUID().toString(), query, TextSignals.normalizeQuery(query),
                "web", 10, false, false, false, "baseline", 1.0, null, List.of(), results,
                0, 10, Instant.now(), null, null);
    }

    private static Feedback rating(String impressionId, String chunkId, int rank, float value) {
        return new Feedback(0, impressionId, chunkId, rank, Signal.RATING, value, "web-user", Instant.now());
    }

    private void vote(String query, String chunkId, int rank, float value, int times) {
        for (int i = 0; i < times; i++) {
            Impression impression = impression(query, chunkId, "other");
            memory.onSettled(impression,
                    List.of(rating(impression.id(), chunkId, rank, value)), 1.0);
        }
    }

    private Map<String, FeedbackMemory.Adjustment> lookup(String query) {
        return memory.adjustments(query, TextSignals.terms(query), null);
    }

    @Test
    void nothingIsAdjustedBeforeAnyFeedbackExists() {
        assertThat(lookup("deployment rollback procedure")).isEmpty();
    }

    @Test
    void oneThumbsUpNudgesTheSameQueryOnTheNextSearch() {
        vote("deployment rollback procedure", "chunk-a", 3, 1f, 1);

        Map<String, FeedbackMemory.Adjustment> adjustments = lookup("deployment rollback procedure");

        assertThat(adjustments).containsKey("chunk-a");
        assertThat(adjustments.get("chunk-a").delta()).isPositive();
    }

    /**
     * Bounded learning: no single vote is decisive, and no amount of voting exceeds the cap. This is
     * what keeps a mis-click from durably distorting a ranking.
     */
    @Test
    void repeatedVotesSaturateRatherThanCompound() {
        vote("deployment rollback procedure", "chunk-a", 3, 1f, 1);
        float afterOne = lookup("deployment rollback procedure").get("chunk-a").delta();

        vote("deployment rollback procedure", "chunk-a", 3, 1f, 8);
        float afterMany = lookup("deployment rollback procedure").get("chunk-a").delta();

        assertThat(afterMany).isGreaterThan(afterOne);
        // max-boost is 0.12 and strength clamps at 1.0.
        assertThat(afterMany).isLessThanOrEqualTo(0.12f);
        assertThat(afterOne).isLessThan(afterMany * 0.6f);
    }

    /** A wrong demotion hides evidence; a wrong promotion only annoys. Hence the asymmetry. */
    @Test
    void demotionsAreWeakerThanPromotions() {
        vote("rollback the deployment", "chunk-up", 1, 1f, 5);
        vote("rollback the deployment", "chunk-down", 2, -1f, 5);

        Map<String, FeedbackMemory.Adjustment> adjustments = lookup("rollback the deployment");

        assertThat(adjustments.get("chunk-up").delta()).isPositive();
        assertThat(adjustments.get("chunk-down").delta()).isNegative();
        assertThat(Math.abs(adjustments.get("chunk-down").delta()))
                .isLessThan(adjustments.get("chunk-up").delta());
    }

    /** Word order and filler must not fork one question into two unrelated memories. */
    @Test
    void reorderedAndFilledQueriesMatchTheSameMemory() {
        vote("deployment rollback procedure", "chunk-a", 1, 1f, 3);

        assertThat(lookup("procedure for the deployment rollback")).containsKey("chunk-a");
    }

    /** The similarity gate is what stops one strong opinion leaking across a whole topic. */
    @Test
    void anUnrelatedQueryIsUnaffected() {
        vote("deployment rollback procedure", "chunk-a", 1, 1f, 3);

        assertThat(lookup("confluence cloud api token")).isEmpty();
        assertThat(lookup("jira issue webhook registration")).isEmpty();
    }

    @Test
    void clearingAVoteWithANeutralRatingWindsTheStrengthBack() {
        vote("deployment rollback procedure", "chunk-a", 1, 1f, 3);
        float boosted = lookup("deployment rollback procedure").get("chunk-a").delta();

        vote("deployment rollback procedure", "chunk-a", 1, -1f, 3);
        Map<String, FeedbackMemory.Adjustment> after = lookup("deployment rollback procedure");

        assertThat(boosted).isPositive();
        assertThat(after.get("chunk-a").delta()).isLessThanOrEqualTo(0f);
    }

    @Test
    void resetClearsLearnedStateEntirely() {
        vote("deployment rollback procedure", "chunk-a", 1, 1f, 2);
        assertThat(repository.count()).isPositive();

        memory.reset();

        assertThat(repository.count()).isZero();
        assertThat(lookup("deployment rollback procedure")).isEmpty();
    }

    /** Learned state is a derived cache: the logs alone must be able to reconstruct it. */
    @Test
    void rebuildReconstructsTheSameStateFromTheLogs() {
        Impression impression = impression("deployment rollback procedure", "chunk-a", "chunk-b");
        List<Feedback> events = List.of(rating(impression.id(), "chunk-a", 1, 1f));
        memory.onSettled(impression, events, 1.0);
        float before = lookup("deployment rollback procedure").get("chunk-a").delta();

        memory.reset();
        assertThat(lookup("deployment rollback procedure")).isEmpty();

        memory.rebuild(List.of(impression), events);

        assertThat(lookup("deployment rollback procedure").get("chunk-a").delta()).isEqualTo(before);
    }
}
