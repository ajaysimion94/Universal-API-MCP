package com.mcpserver.learning;

import java.time.Instant;
import java.util.List;

/**
 * Plain records for the adaptive-ranking subsystem, grouped in one file the way
 * {@code insights/InsightModel} groups its own — these are data carriers between the search path,
 * the writer thread, and the repositories, not domain objects with behaviour.
 */
public final class LearningModel {

    private LearningModel() {
    }

    /** A user signal on one result. Ordered weakest to strongest for readability, not by value. */
    public enum Signal {
        /** The user opened a collapsed source group — weak evidence of interest. */
        EXPAND(0.2f),
        /** The user copied the result body. */
        COPY(0.4f),
        /** The user followed the source link. */
        OPEN(0.5f),
        /** An explicit thumb. Carries its own +1/-1/0 value rather than this weight. */
        RATING(1.0f);

        private final float implicitValue;

        Signal(float implicitValue) {
            this.implicitValue = implicitValue;
        }

        /**
         * The fixed value an implicit signal contributes. These are a prior, not a measurement:
         * at single-user traffic, tuning them would be fitting noise.
         */
        public float implicitValue() {
            return implicitValue;
        }
    }

    /** One result as it was actually served, so feedback can be attributed to a position. */
    public record ServedResult(String chunkId, int rank, float score) {
    }

    /** One served search. {@code reward} and {@code rewardedAt} are null until it settles. */
    public record Impression(
            String id,
            String query,
            String queryNorm,
            String surface,
            int topK,
            boolean web,
            boolean lexicalOnly,
            boolean fromCache,
            String armId,
            double propensity,
            String shadowArm,
            List<Float> context,
            List<ServedResult> results,
            int memoryHits,
            long latencyMs,
            Instant servedAt,
            Instant rewardedAt,
            Double reward
    ) {
        public Impression {
            context = context == null ? List.of() : List.copyOf(context);
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    /** One user signal. {@code chunkId} is "" for a query-level signal, never null. */
    public record Feedback(
            long id,
            String impressionId,
            String chunkId,
            int rank,
            Signal signal,
            float value,
            String actor,
            Instant createdAt
    ) {
    }

    /** A learned preference for one chunk within one query family. */
    public record MemoryEntry(
            String id,
            String queryNorm,
            String querySample,
            float[] embedding,
            String chunkId,
            String sourceName,
            float strength,
            int observations,
            Instant lastSeenAt
    ) {
    }

    /** One discrete fusion-weight arm plus its learned LinUCB state. */
    public record PolicyArm(
            String armId,
            float wVector,
            float wLexical,
            double[][] a,
            double[] b,
            int pulls,
            double rewardSum,
            boolean enabled
    ) {
        public double meanReward() {
            return pulls == 0 ? 0 : rewardSum / pulls;
        }
    }
}
