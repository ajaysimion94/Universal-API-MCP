package com.mcpserver.learning;

import com.mcpserver.learning.LearningModel.Feedback;
import com.mcpserver.learning.LearningModel.Impression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Closes the loop: an impression's reward is unknown when it is served, so it is settled either when
 * an explicit thumb arrives (decisive — settle at once) or when its collection window expires.
 * <p>
 * The scheduled sweep runs on Spring's single default scheduler thread but does no database work
 * itself — it enqueues onto {@link LearningWriter}, so every learning write stays on the one thread
 * that owns them. That is what keeps the shared SQLite connection's contention story unchanged.
 */
@Component
public class RewardSettler {

    private static final Logger log = LoggerFactory.getLogger(RewardSettler.class);
    private static final int SWEEP_BATCH = 100;

    private final ImpressionRepository impressions;
    private final FeedbackRepository feedback;
    private final LearningWriter writer;
    private final List<RewardListener> listeners;
    private final boolean enabled;
    private final long rewardWindowSeconds;
    private final long impressionRetentionDays;

    public RewardSettler(ImpressionRepository impressions,
                         FeedbackRepository feedback,
                         LearningWriter writer,
                         List<RewardListener> listeners,
                         @Value("${learning.enabled:true}") boolean enabled,
                         @Value("${learning.reward-window-seconds:120}") long rewardWindowSeconds,
                         @Value("${learning.impression-retention-days:180}") long impressionRetentionDays) {
        this.impressions = impressions;
        this.feedback = feedback;
        this.writer = writer;
        this.listeners = listeners;
        this.enabled = enabled;
        this.rewardWindowSeconds = rewardWindowSeconds;
        this.impressionRetentionDays = impressionRetentionDays;
    }

    /**
     * An explicit rating is the strongest signal available and will not improve by waiting, so the
     * impression settles immediately rather than sitting out the rest of its window.
     */
    public void settleNow(String impressionId) {
        if (!enabled) return;
        writer.submitDeferred("settle-explicit", () -> settle(impressionId));
    }

    @Scheduled(fixedDelayString = "${learning.settle-interval-ms:30000}")
    public void sweep() {
        if (!enabled) return;
        writer.submitDeferred("settle-sweep", () -> {
            Instant cutoff = Instant.now().minus(rewardWindowSeconds, ChronoUnit.SECONDS);
            List<Impression> due = impressions.findUnsettledBefore(cutoff, SWEEP_BATCH);
            for (Impression impression : due) {
                // Per-item try/catch, matching ConnectionPollingScheduler: one bad row must not
                // stall every impression queued behind it, permanently.
                try {
                    settle(impression);
                } catch (Exception exception) {
                    log.warn("Settling impression {} failed: {}", impression.id(), exception.getMessage());
                }
            }
        });
    }

    /**
     * Keeps the log from growing without bound, while never discarding anything a human reacted to:
     * an impression with feedback is part of the replay corpus and is retained indefinitely. At
     * roughly 1 KB per search, only the ignored ones are worth reclaiming.
     */
    @Scheduled(fixedDelayString = "${learning.prune-interval-ms:86400000}", initialDelay = 300000)
    public void prune() {
        if (!enabled) return;
        writer.submitDeferred("prune-impressions", () -> {
            Instant cutoff = Instant.now().minus(impressionRetentionDays, ChronoUnit.DAYS);
            int removed = impressions.pruneUnrewardedBefore(cutoff);
            if (removed > 0) {
                log.info("Pruned {} impression(s) older than {} days that never drew feedback",
                        removed, impressionRetentionDays);
            }
        });
    }

    /** Replays every impression that ever drew a signal, rebuilding all learned state from the logs. */
    public void rebuildAll() {
        writer.submitDeferred("rebuild", () -> {
            List<Impression> corpus = impressions.findWithFeedback();
            List<Feedback> allFeedback = feedback.findAll();
            for (RewardListener listener : listeners) {
                try {
                    listener.rebuild(corpus, allFeedback);
                } catch (Exception exception) {
                    log.warn("Rebuild failed for {}: {}",
                            listener.getClass().getSimpleName(), exception.getMessage());
                }
            }
            log.info("Rebuilt learned state from {} impression(s) with feedback", corpus.size());
        });
    }

    /** Runs on the writer thread. */
    private void settle(String impressionId) {
        impressions.findById(impressionId).ifPresent(this::settle);
    }

    private void settle(Impression impression) {
        if (impression.rewardedAt() != null) return;
        List<Feedback> events = feedback.findByImpression(impression.id());
        Double reward = RewardCalculator.reward(impression, events);

        impressions.markSettled(impression.id(), Instant.now(), reward);

        // A silent impression is recorded as settled so the sweep stops revisiting it, but it trains
        // nothing: no feedback means the user did not engage, not that the results were poor.
        if (events.isEmpty()) return;

        for (RewardListener listener : listeners) {
            try {
                listener.onSettled(impression, events, reward);
            } catch (Exception exception) {
                log.warn("Learner {} rejected impression {}: {}",
                        listener.getClass().getSimpleName(), impression.id(), exception.getMessage());
            }
        }
    }
}
