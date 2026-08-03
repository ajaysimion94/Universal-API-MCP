package com.mcpserver.learning;

import com.mcpserver.learning.LearningModel.Feedback;
import com.mcpserver.learning.LearningModel.Impression;
import com.mcpserver.learning.LearningModel.ServedResult;
import com.mcpserver.learning.LearningModel.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RewardCalculatorTests {

    private static Impression served(String... chunkIds) {
        List<ServedResult> results = new java.util.ArrayList<>();
        for (int i = 0; i < chunkIds.length; i++) {
            results.add(new ServedResult(chunkIds[i], i + 1, 0.5f));
        }
        return new Impression("imp", "q", "q", "web", 10, false, false, false,
                "baseline", 1.0, null, List.of(), results, 0, 10, Instant.now(), null, null);
    }

    private static Feedback signal(String chunkId, int rank, Signal type, float value) {
        return new Feedback(0, "imp", chunkId, rank, type, value, "web-user", Instant.now());
    }

    @Test
    void noSignalYieldsNoRewardRatherThanANeutralOne() {
        assertThat(RewardCalculator.reward(served("a", "b"), List.of())).isNull();
    }

    /** A perfect ranking — the only liked result is already first — is the top of the scale. */
    @Test
    void likingTheTopResultScoresTheMaximum() {
        Double reward = RewardCalculator.reward(served("a", "b", "c"),
                List.of(signal("a", 1, Signal.RATING, 1f)));

        assertThat(reward).isEqualTo(1.0, within(1e-6));
    }

    /**
     * The whole point of the discount: the same vote is worth less when the system had to be
     * scrolled past to earn it. This is what makes the reward a *ranking* objective.
     */
    @Test
    void theSameThumbsUpScoresLowerWhenItWasBuriedDeeper() {
        Double atTop = RewardCalculator.reward(served("a", "b", "c", "d"),
                List.of(signal("a", 1, Signal.RATING, 1f)));
        Double atBottom = RewardCalculator.reward(served("a", "b", "c", "d"),
                List.of(signal("d", 4, Signal.RATING, 1f)));

        assertThat(atBottom).isLessThan(atTop);
        assertThat(atBottom).isGreaterThan(0.5);
    }

    @Test
    void aThumbsDownAtTheTopIsPenalizedHardest() {
        Double topDown = RewardCalculator.reward(served("a", "b", "c"),
                List.of(signal("a", 1, Signal.RATING, -1f)));
        Double deepDown = RewardCalculator.reward(served("a", "b", "c"),
                List.of(signal("c", 3, Signal.RATING, -1f)));

        assertThat(topDown).isEqualTo(0.0, within(1e-6));
        assertThat(deepDown).isGreaterThan(topDown).isLessThan(0.5);
    }

    @Test
    void implicitSignalsAccumulateButStayWeakerThanAnExplicitThumb() {
        Double implicit = RewardCalculator.reward(served("a", "b"),
                List.of(signal("a", 1, Signal.EXPAND, 0f), signal("a", 1, Signal.OPEN, 0f)));
        Double explicit = RewardCalculator.reward(served("a", "b"),
                List.of(signal("a", 1, Signal.RATING, 1f)));

        // EXPAND 0.2 + OPEN 0.5 = 0.7, short of an explicit +1.
        assertThat(implicit).isLessThan(explicit).isGreaterThan(0.5);
    }

    @Test
    void labelsClampSoNoAmountOfClickingExceedsOneExplicitVote() {
        var labels = RewardCalculator.labels(List.of(
                signal("a", 1, Signal.EXPAND, 0f),
                signal("a", 1, Signal.OPEN, 0f),
                signal("a", 1, Signal.COPY, 0f),
                signal("a", 1, Signal.RATING, 1f)));

        assertThat(labels.get("a")).isEqualTo(1f);
    }

    /**
     * A vote referencing a chunk that is not in the served list (a stale client, or a result the
     * pipeline has since dropped) must not corrupt the scale.
     */
    @Test
    void signalsOnResultsThatWereNotServedAreIgnored() {
        assertThat(RewardCalculator.reward(served("a", "b"),
                List.of(signal("ghost", 9, Signal.RATING, 1f)))).isNull();
    }

    @Test
    void mixedPositiveAndNegativeVotesLandBetweenTheExtremes() {
        Double reward = RewardCalculator.reward(served("a", "b", "c"),
                List.of(signal("a", 1, Signal.RATING, -1f), signal("b", 2, Signal.RATING, 1f)));

        assertThat(reward).isBetween(0.0, 0.5);
    }
}
