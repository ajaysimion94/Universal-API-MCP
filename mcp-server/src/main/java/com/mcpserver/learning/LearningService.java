package com.mcpserver.learning;

import com.mcpserver.learning.LearningModel.Impression;
import com.mcpserver.learning.LearningModel.ServedResult;
import com.mcpserver.rag.retrieval.TextSignals;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The single seam between the search pipeline and the adaptive-ranking subsystem.
 * <p>
 * {@code SearchService} talks only to this class, so with {@code learning.enabled=false} the whole
 * subsystem reduces to a handful of no-ops on the request path and served ranking is identical to a
 * build without it.
 */
@Component
public class LearningService {

    /** Surface tag for searches served to the web UI, the only surface that can send feedback. */
    public static final String SURFACE_WEB = "web";
    /** MCP searches are logged for the latency guard but never receive signals or train anything. */
    public static final String SURFACE_MCP = "mcp";

    private final LearningWriter writer;
    private final FeedbackMemory memory;
    private final RankingPolicy policy;
    private final boolean enabled;

    public LearningService(LearningWriter writer,
                           FeedbackMemory memory,
                           RankingPolicy policy,
                           @Value("${learning.enabled:true}") boolean enabled) {
        this.writer = writer;
        this.memory = memory;
        this.policy = policy;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Everything decided before retrieval runs, carried through the pipeline so the impression can
     * record what the policy actually chose rather than what it would choose now.
     * <p>
     * The query is normalized once, here, because both the memory lookup and the impression row need
     * the same key and recomputing it invites drift.
     */
    public record RankingDecision(String query, String queryNorm, Set<String> queryTerms,
                                  RankingPolicy.Selection selection) {

        /** Unlearned decision — the (1, 1) blend. Used by tests and when learning is disabled. */
        public static RankingDecision of(String query) {
            Set<String> terms = Set.copyOf(TextSignals.terms(query));
            return new RankingDecision(query, TextSignals.normalizeQuery(query), terms,
                    RankingPolicy.Selection.baseline(RankingPolicy.context(query, terms)));
        }

        public float vectorWeight() {
            return selection.wVector();
        }

        public float lexicalWeight() {
            return selection.wLexical();
        }

        /** Part of the search cache key: a response produced by one arm must not be served as another. */
        public String armId() {
            return selection.armId();
        }
    }

    public RankingDecision decide(String query) {
        Set<String> terms = Set.copyOf(TextSignals.terms(query));
        if (!enabled) return RankingDecision.of(query);
        return new RankingDecision(query, TextSignals.normalizeQuery(query), terms,
                policy.select(query, terms));
    }

    /**
     * Score deltas learned from past feedback for this query, keyed by chunk id.
     * <p>
     * Callers must apply these <strong>after</strong> the relevance filter, never before — see
     * {@code SearchService}. Empty map when learning is off or nothing similar has been learned.
     */
    public Map<String, FeedbackMemory.Adjustment> memoryAdjustments(
            RankingDecision decision, float[] queryEmbedding) {
        if (!enabled) return Map.of();
        // Backfills the query embedding onto entries learned while the vector leg was down, so a
        // paraphrase of an already-voted query can match on cosine next time.
        if (queryEmbedding != null && memory.isActive()) {
            writer.submitDeferred("memory-embedding",
                    () -> memory.rememberQueryEmbedding(decision.queryNorm(), queryEmbedding));
        }
        return memory.adjustments(decision.query(), decision.queryTerms(), queryEmbedding);
    }

    /**
     * Records what was served. Returns the impression id to echo to the client, or null when
     * learning is off — the client simply renders no vote controls in that case.
     * <p>
     * Cache hits are recorded too, with {@code fromCache=true}. Without that, "what did I actually
     * see" is unrecoverable for repeat queries inside the cache TTL, which is exactly the traffic
     * the feedback memory feeds on.
     */
    public String recordImpression(RankingDecision decision,
                                   String surface,
                                   int topK,
                                   boolean web,
                                   boolean lexicalOnly,
                                   boolean fromCache,
                                   List<ServedResult> results,
                                   int memoryHits,
                                   long latencyMs) {
        if (!enabled) return null;
        String id = UUID.randomUUID().toString();
        RankingPolicy.Selection selection = decision.selection();
        List<Float> context = new java.util.ArrayList<>(selection.context().length);
        for (float feature : selection.context()) context.add(feature);
        writer.recordImpression(new Impression(
                id,
                decision.query(),
                decision.queryNorm(),
                surface,
                topK,
                web,
                lexicalOnly,
                fromCache,
                selection.armId(),
                selection.propensity(),
                selection.shadowArm(),
                context,
                results,
                memoryHits,
                latencyMs,
                Instant.now(),
                null,
                null));
        return id;
    }
}
