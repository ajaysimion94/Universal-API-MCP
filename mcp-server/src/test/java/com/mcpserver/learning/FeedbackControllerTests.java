package com.mcpserver.learning;

import com.mcpserver.learning.LearningModel.Impression;
import com.mcpserver.learning.LearningModel.ServedResult;
import com.mcpserver.learning.LearningModel.Signal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class FeedbackControllerTests {

    @Autowired
    FeedbackController controller;

    @Autowired
    ImpressionRepository impressions;

    @Autowired
    FeedbackRepository feedback;

    @Autowired
    LearningWriter writer;

    private String seedImpression() {
        String id = UUID.randomUUID().toString();
        impressions.save(new Impression(id, "rollback steps", "rollback steps", "web", 5,
                false, false, false, "baseline", 1.0, null, List.of(),
                List.of(new ServedResult("chunk-a", 1, 0.7f), new ServedResult("chunk-b", 2, 0.3f)),
                0, 20, Instant.now(), null, null));
        return id;
    }

    private static FeedbackController.EventBody event(String chunkId, int rank, String signal, Double value) {
        FeedbackController.EventBody body = new FeedbackController.EventBody();
        body.chunkId = chunkId;
        body.rank = rank;
        body.signal = signal;
        body.value = value;
        return body;
    }

    private static FeedbackController.FeedbackRequest request(String impressionId,
                                                              FeedbackController.EventBody... events) {
        FeedbackController.FeedbackRequest request = new FeedbackController.FeedbackRequest();
        request.impressionId = impressionId;
        request.events = List.of(events);
        return request;
    }

    @Test
    void acceptsABatchOfMixedSignalsAndPersistsThemAll() {
        String id = seedImpression();

        Map<String, Object> response = controller.submit(request(id,
                event("chunk-a", 1, "RATING", 1.0),
                event("chunk-b", 2, "EXPAND", null),
                event("chunk-b", 2, "OPEN", null)), "web-user");

        assertThat(response).containsEntry("accepted", 3).containsEntry("learning", true);
        assertThat(writer.awaitDrain(5000)).isTrue();
        assertThat(feedback.findByImpression(id)).hasSize(3);
    }

    /** Implicit signals carry a fixed server-side weight; a client cannot inflate its own vote. */
    @Test
    void implicitSignalValuesComeFromTheServerNotTheClient() {
        String id = seedImpression();

        controller.submit(request(id, event("chunk-a", 1, "EXPAND", 99.0)), "web-user");
        assertThat(writer.awaitDrain(5000)).isTrue();

        assertThat(feedback.findByImpression(id).get(0).value())
                .isEqualTo(Signal.EXPAND.implicitValue());
    }

    @Test
    void ratingsAreNormalizedToPlusOneMinusOneOrZero() {
        String id = seedImpression();

        controller.submit(request(id, event("chunk-a", 1, "RATING", 42.0)), "web-user");
        assertThat(writer.awaitDrain(5000)).isTrue();

        assertThat(feedback.findByImpression(id).get(0).value()).isEqualTo(1f);
    }

    /**
     * The load-bearing behaviour for a client whose localStorage outlived the database: a stale vote
     * is absorbed quietly, never surfaced as an error in a page the user is reading.
     */
    @Test
    void unknownImpressionIsIgnoredWithTwoHundredNotAnError() {
        Map<String, Object> response = controller.submit(
                request("no-such-impression", event("chunk-a", 1, "RATING", 1.0)), "web-user");

        assertThat(response)
                .containsEntry("accepted", 0)
                .containsEntry("ignored", "unknown impression");
    }

    @Test
    void malformedRequestsAreRejected() {
        String id = seedImpression();

        assertThatThrownBy(() -> controller.submit(request(null, event("c", 1, "RATING", 1.0)), "web-user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("impressionId");

        assertThatThrownBy(() -> controller.submit(request(id), "web-user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");

        assertThatThrownBy(() -> controller.submit(request(id, event("c", 1, "SHRUG", null)), "web-user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown signal");
    }

    /** An explicit thumb settles the impression immediately rather than waiting out the window. */
    @Test
    void anExplicitRatingSettlesTheImpressionWithoutWaitingForTheWindow() {
        String id = seedImpression();

        controller.submit(request(id, event("chunk-a", 1, "RATING", 1.0)), "web-user");
        assertThat(writer.awaitDrain(5000)).isTrue();

        Impression settled = impressions.findById(id).orElseThrow();
        assertThat(settled.rewardedAt()).isNotNull();
        assertThat(settled.reward()).isEqualTo(1.0);
    }

    /** Implicit signals alone leave the impression collecting until its window expires. */
    @Test
    void implicitSignalsDoNotSettleTheImpressionEarly() {
        String id = seedImpression();

        controller.submit(request(id, event("chunk-a", 1, "EXPAND", null)), "web-user");
        assertThat(writer.awaitDrain(5000)).isTrue();

        assertThat(impressions.findById(id).orElseThrow().rewardedAt()).isNull();
    }
}
