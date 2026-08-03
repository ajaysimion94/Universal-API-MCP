package com.mcpserver.learning;

import com.mcpserver.cache.CacheService;
import com.mcpserver.learning.LearningModel.Feedback;
import com.mcpserver.learning.LearningModel.Impression;
import com.mcpserver.learning.LearningModel.MemoryEntry;
import com.mcpserver.rag.retrieval.TextSignals;
import com.mcpserver.repositories.ChunkRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Remembers which chunks the user liked for a given kind of question, and nudges them when a
 * similar question comes back.
 * <p>
 * This is the learner that is actually visible at single-user traffic: one vote changes the next
 * search for that query family, where the bandit would need weeks of data to move at all.
 * <p>
 * <strong>Reads never touch the database.</strong> The whole table is held as an immutable snapshot
 * rebuilt on write; the writer thread is the only mutator, so the search path needs no locking. At
 * the 2,000-entry cap that is a few megabytes, and an inverted term index keeps a typical lookup to
 * tens of candidates rather than a full scan.
 */
@Component
public class FeedbackMemory implements RewardListener {

    private static final Logger log = LoggerFactory.getLogger(FeedbackMemory.class);
    /** Below this, a decayed preference is indistinguishable from noise and is skipped. */
    private static final float NEGLIGIBLE_STRENGTH = 0.02f;

    private final FeedbackMemoryRepository repository;
    private final ChunkRepository chunks;
    private final CacheService cacheService;
    private final boolean enabled;
    private final float maxBoost;
    private final float maxDemote;
    private final float learningRate;
    private final float jaccardThreshold;
    private final float cosineThreshold;
    private final double halfLifeDays;
    private final int maxEntries;

    /** Immutable snapshot; replaced wholesale by the writer thread. */
    private volatile Snapshot snapshot = Snapshot.empty();

    public FeedbackMemory(FeedbackMemoryRepository repository,
                          ChunkRepository chunks,
                          CacheService cacheService,
                          @Value("${learning.enabled:true}") boolean enabled,
                          @Value("${learning.memory.max-boost:0.12}") float maxBoost,
                          @Value("${learning.memory.max-demote:0.06}") float maxDemote,
                          @Value("${learning.memory.learning-rate:0.34}") float learningRate,
                          @Value("${learning.memory.jaccard-threshold:0.60}") float jaccardThreshold,
                          @Value("${learning.memory.cosine-threshold:0.92}") float cosineThreshold,
                          @Value("${learning.memory.half-life-days:45}") double halfLifeDays,
                          @Value("${learning.memory.max-entries:2000}") int maxEntries) {
        this.repository = repository;
        this.chunks = chunks;
        this.cacheService = cacheService;
        this.enabled = enabled;
        this.maxBoost = maxBoost;
        this.maxDemote = maxDemote;
        this.learningRate = learningRate;
        this.jaccardThreshold = jaccardThreshold;
        this.cosineThreshold = cosineThreshold;
        this.halfLifeDays = halfLifeDays;
        this.maxEntries = maxEntries;
    }

    @PostConstruct
    void load() {
        if (!enabled) return;
        try {
            reload();
            if (snapshot.entries().size() > 0) {
                log.info("Feedback memory loaded: {} learned preference(s)", snapshot.entries().size());
            }
        } catch (Exception exception) {
            log.warn("Feedback memory not loaded at startup — {} (schema may not be ready)",
                    exception.getMessage());
        }
    }

    /** An immutable read view: the entry list plus an inverted index from term to entries. */
    private record Snapshot(List<MemoryEntry> entries, Map<String, List<MemoryEntry>> byTerm) {

        static Snapshot empty() {
            return new Snapshot(List.of(), Map.of());
        }

        static Snapshot of(List<MemoryEntry> entries) {
            Map<String, List<MemoryEntry>> index = new HashMap<>();
            for (MemoryEntry entry : entries) {
                for (String term : TextSignals.terms(entry.queryNorm())) {
                    index.computeIfAbsent(term, key -> new ArrayList<>()).add(entry);
                }
            }
            return new Snapshot(List.copyOf(entries), Map.copyOf(index));
        }
    }

    /** A learned adjustment for one chunk, already scaled by similarity and time decay. */
    public record Adjustment(String chunkId, float delta, float strength) {
    }

    public boolean isActive() {
        return enabled && !snapshot.entries().isEmpty();
    }

    public int size() {
        return snapshot.entries().size();
    }

    /**
     * Score deltas for the current query, keyed by chunk id. Empty when nothing has been learned
     * that is similar enough, which is the overwhelmingly common case early on.
     *
     * @param queryEmbedding the query vector already computed for retrieval, or null in lexical-only
     *                       mode — free to reuse, and the reason paraphrase matching costs nothing
     */
    public Map<String, Adjustment> adjustments(String query, Set<String> queryTerms, float[] queryEmbedding) {
        if (!enabled) return Map.of();
        Snapshot current = snapshot;
        if (current.entries().isEmpty()) return Map.of();

        // Shortlist by shared term first: a full scan would be correct but wasteful, and entries
        // with no term in common can still match on cosine, so the embedding path scans separately.
        Set<MemoryEntry> candidates = new HashSet<>();
        for (String term : queryTerms) {
            List<MemoryEntry> bucket = current.byTerm().get(term);
            if (bucket != null) candidates.addAll(bucket);
        }
        if (queryEmbedding != null && queryEmbedding.length > 0) {
            candidates.addAll(current.entries());
        }

        Instant now = Instant.now();
        Map<String, Adjustment> adjustments = new HashMap<>();
        for (MemoryEntry entry : candidates) {
            float similarity = similarityWeight(entry, queryTerms, queryEmbedding);
            if (similarity <= 0f) continue;

            float decayed = decayedStrength(entry, now);
            if (Math.abs(decayed) < NEGLIGIBLE_STRENGTH) continue;

            float delta = similarity * decayed * (decayed >= 0 ? maxBoost : maxDemote);
            // Two query families can both match a paraphrase; keep the strongest opinion rather
            // than summing, so overlapping memories cannot compound into an unbounded nudge.
            adjustments.merge(entry.chunkId(), new Adjustment(entry.chunkId(), delta, decayed),
                    (left, right) -> Math.abs(left.delta()) >= Math.abs(right.delta()) ? left : right);
        }
        return adjustments;
    }

    /**
     * How strongly this entry applies to the query, in [0, 1].
     * <p>
     * Two gates, either sufficient: term overlap catches reworded-but-same-words queries, cosine
     * catches genuine paraphrases. Neither alone is safe — exact matching is too brittle
     * ("rollback procedure" vs "how do I roll back"), Jaccard misses paraphrases, and cosine alone
     * occasionally fires on same-topic-different-intent questions.
     * <p>
     * The result is <em>rescaled from the threshold</em>, not passed through raw: a barely-matching
     * query contributes essentially nothing and only a near-exact repeat earns the full nudge. This
     * is the guard that stops one strong opinion leaking across a whole topic.
     */
    private float similarityWeight(MemoryEntry entry, Set<String> queryTerms, float[] queryEmbedding) {
        float best = 0f;

        float jaccard = TextSignals.jaccard(queryTerms, TextSignals.terms(entry.queryNorm()));
        if (jaccard >= jaccardThreshold) {
            best = rescale(jaccard, jaccardThreshold);
        }
        if (queryEmbedding != null && entry.embedding() != null) {
            float cosine = TextSignals.cosine(queryEmbedding, entry.embedding());
            if (cosine >= cosineThreshold) {
                best = Math.max(best, rescale(cosine, cosineThreshold));
            }
        }
        return best;
    }

    private static float rescale(float similarity, float threshold) {
        if (threshold >= 1f) return 1f;
        return Math.max(0f, Math.min(1f, (similarity - threshold) / (1f - threshold)));
    }

    /**
     * Half-life decay computed at read time, never by a sweep: the rows stay intact for the replay
     * harness, and an opinion the user stopped reinforcing quietly stops mattering.
     */
    private float decayedStrength(MemoryEntry entry, Instant now) {
        double days = Duration.between(entry.lastSeenAt(), now).toMillis() / 86_400_000.0;
        if (days <= 0) return entry.strength();
        double decay = Math.pow(0.5, days / halfLifeDays);
        return (float) (entry.strength() * decay);
    }

    // ── RewardListener ────────────────────────────────────────────────────────────────────────

    @Override
    public void onSettled(Impression impression, List<Feedback> feedback, Double reward) {
        if (!enabled) return;
        Map<String, Float> labels = RewardCalculator.labels(feedback);
        if (labels.isEmpty()) return;

        Map<String, MemoryEntry> existing = new HashMap<>();
        for (MemoryEntry entry : snapshot.entries()) {
            if (entry.queryNorm().equals(impression.queryNorm())) existing.put(entry.chunkId(), entry);
        }

        Instant now = Instant.now();
        boolean changed = false;
        for (Map.Entry<String, Float> label : labels.entrySet()) {
            if (label.getKey().isBlank() || label.getValue() == 0f) continue;
            MemoryEntry prior = existing.get(label.getKey());

            // Bounded update: three consistent votes reach full strength, so one vote is never
            // decisive and a hundred cannot exceed the cap.
            float strength = clamp((prior == null ? 0f : prior.strength()) + learningRate * label.getValue());
            MemoryEntry updated = new MemoryEntry(
                    prior == null ? UUID.randomUUID().toString() : prior.id(),
                    impression.queryNorm(),
                    impression.query(),
                    prior == null ? null : prior.embedding(),
                    label.getKey(),
                    prior != null && !prior.sourceName().isBlank()
                            ? prior.sourceName()
                            : chunks.findSourceName(label.getKey()),
                    strength,
                    (prior == null ? 0 : prior.observations()) + 1,
                    now);
            repository.save(updated);
            changed = true;
        }

        if (changed) {
            evictIfOverCapacity(now);
            reload();
            // The search cache would otherwise serve the pre-vote ordering for another 30 seconds,
            // which is exactly the window in which a user checks whether their vote did anything.
            cacheService.invalidateSearchResults();
        }
    }

    @Override
    public void reset() {
        repository.deleteAll();
        snapshot = Snapshot.empty();
        cacheService.invalidateSearchResults();
    }

    /**
     * Attaches the query embedding to every entry of a family, so later paraphrases can match on
     * cosine. Called from the search path (via the writer) when a query with learned entries is
     * served and the vector leg is up — the embedding is already computed for retrieval.
     */
    public void rememberQueryEmbedding(String queryNorm, float[] embedding) {
        if (!enabled || embedding == null || embedding.length == 0) return;
        boolean changed = false;
        for (MemoryEntry entry : snapshot.entries()) {
            if (!entry.queryNorm().equals(queryNorm) || entry.embedding() != null) continue;
            repository.save(new MemoryEntry(entry.id(), entry.queryNorm(), entry.querySample(),
                    embedding, entry.chunkId(), entry.sourceName(), entry.strength(),
                    entry.observations(), entry.lastSeenAt()));
            changed = true;
        }
        if (changed) reload();
    }

    /** Top entries by decayed magnitude, for the Learning panel. */
    public List<Map<String, Object>> describe(int limit) {
        Instant now = Instant.now();
        return snapshot.entries().stream()
                .sorted(Comparator.comparingDouble(
                        (MemoryEntry entry) -> Math.abs(decayedStrength(entry, now))).reversed())
                .limit(limit)
                .map(entry -> Map.<String, Object>of(
                        "query", entry.querySample(),
                        "chunkId", entry.chunkId(),
                        "sourceName", entry.sourceName(),
                        "strength", round(entry.strength()),
                        "decayedStrength", round(decayedStrength(entry, now)),
                        "observations", entry.observations(),
                        "lastSeenAt", entry.lastSeenAt().toString()))
                .toList();
    }

    private void evictIfOverCapacity(Instant now) {
        List<MemoryEntry> all = repository.findAll();
        if (all.size() <= maxEntries) return;
        all.stream()
                .sorted(Comparator.comparingDouble(entry -> Math.abs(decayedStrength(entry, now))))
                .limit(all.size() - (long) maxEntries)
                .forEach(entry -> repository.deleteById(entry.id()));
    }

    private void reload() {
        snapshot = Snapshot.of(repository.findAll());
    }

    private static float clamp(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }

    private static double round(float value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
