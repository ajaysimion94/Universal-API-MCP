package com.mcpserver.learning;

import com.mcpserver.learning.LearningModel.Feedback;
import com.mcpserver.learning.LearningModel.Impression;
import com.mcpserver.learning.LearningModel.ServedResult;
import com.mcpserver.learning.LearningModel.Signal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LearningCaptureTests {

    @Autowired
    ImpressionRepository impressions;

    @Autowired
    FeedbackRepository feedback;

    @Autowired
    LearningWriter writer;

    private Impression impression(String id, Instant servedAt) {
        return new Impression(id, "deployment rollback", "deployment rollback", "web", 5,
                false, false, false, "baseline", 1.0, null, List.of(0.1f, 0.2f),
                List.of(new ServedResult("chunk-a", 1, 0.71f), new ServedResult("chunk-b", 2, 0.42f)),
                0, 37, servedAt, null, null);
    }

    @Test
    void impressionRoundTripsIncludingTheServedOrdering() {
        String id = UUID.randomUUID().toString();
        impressions.save(impression(id, Instant.now()));

        Impression loaded = impressions.findById(id).orElseThrow();
        assertThat(loaded.query()).isEqualTo("deployment rollback");
        assertThat(loaded.armId()).isEqualTo("baseline");
        assertThat(loaded.latencyMs()).isEqualTo(37);
        assertThat(loaded.reward()).isNull();
        // The compact {c,r,s} wire form must survive the round trip — replay depends on it.
        assertThat(loaded.results()).containsExactly(
                new ServedResult("chunk-a", 1, 0.71f),
                new ServedResult("chunk-b", 2, 0.42f));
        assertThat(loaded.context()).containsExactly(0.1f, 0.2f);
    }

    /**
     * The UNIQUE key is what makes the feedback POST idempotent. Without it, a user toggling a thumb
     * would accumulate contradictory rows and the reward would depend on click count.
     */
    @Test
    void repeatedSignalOnTheSameResultReplacesRatherThanDuplicates() {
        String id = UUID.randomUUID().toString();
        impressions.save(impression(id, Instant.now()));

        feedback.saveAll(List.of(new Feedback(0, id, "chunk-a", 1, Signal.RATING, 1f, "web-user", Instant.now())));
        feedback.saveAll(List.of(new Feedback(0, id, "chunk-a", 1, Signal.RATING, -1f, "web-user", Instant.now())));

        List<Feedback> stored = feedback.findByImpression(id);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).value()).isEqualTo(-1f);
        assertThat(feedback.hasRating(id)).isTrue();
    }

    @Test
    void distinctSignalsOnTheSameResultCoexist() {
        String id = UUID.randomUUID().toString();
        impressions.save(impression(id, Instant.now()));

        feedback.saveAll(List.of(
                new Feedback(0, id, "chunk-a", 1, Signal.EXPAND, 0.2f, "web-user", Instant.now()),
                new Feedback(0, id, "chunk-a", 1, Signal.OPEN, 0.5f, "web-user", Instant.now())));

        assertThat(feedback.findByImpression(id)).extracting(Feedback::signal)
                .containsExactlyInAnyOrder(Signal.EXPAND, Signal.OPEN);
    }

    @Test
    void unsettledImpressionsPastTheRewardWindowAreFound() {
        String stale = UUID.randomUUID().toString();
        String fresh = UUID.randomUUID().toString();
        impressions.save(impression(stale, Instant.now().minus(10, ChronoUnit.MINUTES)));
        impressions.save(impression(fresh, Instant.now()));

        List<String> due = impressions
                .findUnsettledBefore(Instant.now().minus(2, ChronoUnit.MINUTES), 100)
                .stream().map(Impression::id).toList();

        assertThat(due).contains(stale).doesNotContain(fresh);
    }

    @Test
    void settlingRecordsTheRewardAndRemovesItFromTheDueQueue() {
        String id = UUID.randomUUID().toString();
        impressions.save(impression(id, Instant.now().minus(10, ChronoUnit.MINUTES)));

        impressions.markSettled(id, Instant.now(), 0.83);

        assertThat(impressions.findById(id).orElseThrow().reward()).isEqualTo(0.83);
        assertThat(impressions.findUnsettledBefore(Instant.now(), 100).stream().map(Impression::id))
                .doesNotContain(id);
    }

    /**
     * Retention keeps anything a human reacted to: those rows are the replay corpus. Only searches
     * that were served and ignored are disposable.
     */
    @Test
    void pruningKeepsImpressionsThatReceivedFeedbackAndDropsTheRest() {
        String ignored = UUID.randomUUID().toString();
        String rated = UUID.randomUUID().toString();
        Instant old = Instant.now().minus(400, ChronoUnit.DAYS);
        impressions.save(impression(ignored, old));
        impressions.save(impression(rated, old));
        feedback.saveAll(List.of(
                new Feedback(0, rated, "chunk-a", 1, Signal.RATING, 1f, "web-user", Instant.now())));

        impressions.pruneUnrewardedBefore(Instant.now().minus(180, ChronoUnit.DAYS));

        assertThat(impressions.findById(ignored)).isEmpty();
        assertThat(impressions.findById(rated)).isPresent();
    }

    /** The writer must accept work from a request thread and never make that thread wait on a write. */
    @Test
    void writerPersistsAsynchronouslyWithoutBlockingTheCaller() {
        String id = UUID.randomUUID().toString();

        writer.recordImpression(impression(id, Instant.now()));
        writer.recordFeedback(List.of(
                new Feedback(0, id, "chunk-a", 1, Signal.COPY, 0.4f, "web-user", Instant.now())));
        assertThat(writer.awaitDrain(5000)).isTrue();

        assertThat(impressions.findById(id)).isPresent();
        assertThat(feedback.findByImpression(id)).hasSize(1);
        assertThat(writer.droppedWrites()).isZero();
    }
}
