package com.mcpserver.learning;

import com.mcpserver.learning.LearningModel.Feedback;
import com.mcpserver.learning.LearningModel.Impression;

import java.util.List;

/**
 * Implemented by anything that learns from a settled impression — the feedback memory and the
 * ranking policy. {@code RewardSettler} injects every implementation as a {@code List}, the same
 * fan-out shape {@code ConnectionPollingScheduler} uses for {@code SourceConnector}, so adding a
 * third learner later is a new bean and no edit to the settler.
 * <p>
 * Every method runs on the {@code learning-writer} thread, so implementations may write freely and
 * need no locking against each other.
 */
public interface RewardListener {

    /**
     * @param reward the settled reward, or null when the impression drew no usable signal — listeners
     *               that only care about explicit labels can still read {@code feedback} directly
     */
    void onSettled(Impression impression, List<Feedback> feedback, Double reward);

    /** Drop all learned state, keeping the underlying logs. */
    void reset();

    /** Rebuild learned state from scratch by replaying the logs, oldest first. */
    default void rebuild(List<Impression> impressions, List<Feedback> allFeedback) {
        reset();
        var byImpression = allFeedback.stream()
                .collect(java.util.stream.Collectors.groupingBy(Feedback::impressionId));
        for (Impression impression : impressions) {
            List<Feedback> events = byImpression.getOrDefault(impression.id(), List.of());
            if (events.isEmpty()) continue;
            onSettled(impression, events, RewardCalculator.reward(impression, events));
        }
    }
}
