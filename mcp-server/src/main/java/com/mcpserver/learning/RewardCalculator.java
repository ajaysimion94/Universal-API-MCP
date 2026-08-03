package com.mcpserver.learning;

import com.mcpserver.learning.LearningModel.Feedback;
import com.mcpserver.learning.LearningModel.Impression;
import com.mcpserver.learning.LearningModel.ServedResult;
import com.mcpserver.learning.LearningModel.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a bag of user signals into one scalar in [0, 1] — a graded nDCG over feedback labels.
 * <p>
 * Pure and static so it can be exercised directly by tests and by the offline replay harness without
 * a Spring context.
 * <p>
 * <strong>Position bias</strong> is handled the standard IR way rather than with a propensity
 * correction: the {@code 1/log2(rank+1)} discount <em>is</em> the objective. A liked chunk at rank 1
 * scores higher than the same chunk at rank 8, and a disliked chunk at rank 1 is penalized harder
 * than at rank 8 — which is exactly what a ranking policy should be optimized for, and needs no
 * examination model, which could not be estimated from a single user's clicks anyway.
 */
public final class RewardCalculator {

    private RewardCalculator() {
    }

    /**
     * Per-chunk label in [-1, +1]. Explicit ratings carry their own signed value; implicit signals
     * contribute their fixed weight. Several signals on one chunk accumulate, then clamp.
     */
    public static Map<String, Float> labels(List<Feedback> feedback) {
        Map<String, Float> values = new HashMap<>();
        for (Feedback event : feedback) {
            if (event.chunkId() == null || event.chunkId().isBlank()) continue;
            float contribution = event.signal() == Signal.RATING
                    ? event.value()
                    : event.signal().implicitValue();
            values.merge(event.chunkId(), contribution, Float::sum);
        }
        values.replaceAll((chunkId, value) -> clamp(value, -1f, 1f));
        return values;
    }

    /**
     * @return the reward in [0, 1], or null when the impression carries no usable signal at all.
     *         Null means "the user didn't bother", not "the results were mediocre" — at single-user
     *         traffic, folding silence in as a neutral 0.5 would drown the real signal.
     */
    public static Double reward(Impression impression, List<Feedback> feedback) {
        Map<String, Float> labels = labels(feedback);
        if (labels.isEmpty()) return null;

        double actual = 0;
        int positives = 0;
        for (ServedResult result : impression.results()) {
            Float value = labels.get(result.chunkId());
            if (value == null || value == 0f) continue;
            actual += value / log2(result.rank() + 1.0);
            if (value > 0) positives++;
        }
        // A signal on a chunk that is not in the served list (a stale client, or a result that has
        // since been re-ranked out) contributes nothing rather than corrupting the scale.
        if (positives == 0 && actual == 0) return null;

        double ideal = 0;
        for (int i = 1; i <= positives; i++) ideal += 1.0 / log2(i + 1.0);

        double normalized = clamp(actual / Math.max(ideal, 1.0), -1.0, 1.0);
        return 0.5 + 0.5 * normalized;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
