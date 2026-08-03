package com.mcpserver.learning;

import com.mcpserver.learning.LearningModel.Feedback;
import com.mcpserver.learning.LearningModel.Impression;
import com.mcpserver.learning.LearningModel.PolicyArm;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A disjoint LinUCB contextual bandit over the retrieval fusion weights.
 * <p>
 * <strong>Read this before enabling it.</strong> The only thing it controls — the vector/lexical
 * blend — sits upstream of a cross-encoder that dominates the final ordering, and with 40+40
 * candidates retrieved from a few hundred chunks, reweighting rarely changes what the reranker even
 * sees. Its expected effect is close to zero and, at single-user traffic, distinguishing it from
 * noise needs roughly 150–250 rewarded impressions, i.e. weeks of daily use. It ships disabled;
 * {@code learning.bandit.shadow-mode} collects the data needed to judge it at zero user-visible
 * risk. The replay harness decides its fate, not intuition.
 * <p>
 * <strong>Cold start is today's behaviour, exactly.</strong> With {@code A = I} and {@code b = 0}
 * every arm's estimate is 0 and every confidence bonus is identical, so all arms tie and the
 * tie-break is declaration order — {@code baseline} first, which is the unweighted (1, 1) fusion.
 * The sole deviation from a build without this class is exploration: with probability epsilon, one
 * search in ten is served by a different, bounded blend.
 * <p>
 * Plain Java: d = 4 means a 4×4 Gauss-Jordan inverse, which also satisfies the project's
 * in-process-inference-only constraint — ONNX Runtime's Java binding cannot train anything.
 */
@Component
public class RankingPolicy implements RewardListener {

    private static final Logger log = LoggerFactory.getLogger(RankingPolicy.class);

    /** Context dimension: [bias, length, identifier-ness, question-ness]. */
    static final int DIMENSION = 4;

    static final String BASELINE_ARM = "baseline";

    /**
     * Weights sum to 2 across every arm, so {@code baseline} reproduces the unweighted fusion
     * exactly and RRF score magnitudes stay comparable when the policy switches arms.
     * Declaration order is the tie-break, so baseline must stay first.
     */
    private static final List<PolicyArm> SEED_ARMS = List.of(
            seed(BASELINE_ARM, 1.00f, 1.00f),
            seed("vec-lean", 1.30f, 0.70f),
            seed("lex-lean", 0.70f, 1.30f),
            seed("vec-strong", 1.60f, 0.40f),
            seed("lex-strong", 0.40f, 1.60f));

    /** An arm is disabled once it is this far below baseline's mean over enough pulls. */
    private static final double GUARDRAIL_MARGIN = 0.15;
    private static final int GUARDRAIL_MIN_PULLS = 20;

    private final PolicyArmRepository repository;
    private final boolean learningEnabled;
    private final boolean banditEnabled;
    private final boolean shadowMode;
    private final double epsilon;
    private final double alpha;

    /** Seeded only in tests; production uses ThreadLocalRandom. */
    private Random random;

    private volatile List<PolicyArm> arms = List.of();

    public RankingPolicy(PolicyArmRepository repository,
                         @Value("${learning.enabled:true}") boolean learningEnabled,
                         @Value("${learning.bandit.enabled:false}") boolean banditEnabled,
                         @Value("${learning.bandit.shadow-mode:false}") boolean shadowMode,
                         @Value("${learning.bandit.epsilon:0.10}") double epsilon,
                         @Value("${learning.bandit.alpha:0.35}") double alpha) {
        this.repository = repository;
        this.learningEnabled = learningEnabled;
        this.banditEnabled = banditEnabled;
        this.shadowMode = shadowMode;
        this.epsilon = epsilon;
        this.alpha = alpha;
    }

    @PostConstruct
    void start() {
        if (!learningEnabled) return;
        try {
            ensureSeeded();
        } catch (Exception exception) {
            log.warn("Ranking policy not initialised — {} (schema may not be ready)", exception.getMessage());
        }
    }

    /** What the policy chose, and what it would have chosen — the two differ only in shadow mode. */
    public record Selection(String armId, float wVector, float wLexical,
                            double propensity, String shadowArm, float[] context) {

        static Selection baseline(float[] context) {
            return new Selection(BASELINE_ARM, 1f, 1f, 1.0, null, context);
        }
    }

    public boolean isBanditEnabled() {
        return banditEnabled;
    }

    public boolean isShadowMode() {
        return shadowMode;
    }

    /**
     * Picks the fusion weights for one search.
     * <p>
     * Selection happens <em>before</em> the search cache is consulted, which is why the context can
     * only use features derivable from the query string — nothing from retrieval exists yet.
     */
    public Selection select(String query, Set<String> queryTerms) {
        float[] context = context(query, queryTerms);
        if (!learningEnabled || (!banditEnabled && !shadowMode)) {
            return Selection.baseline(context);
        }

        List<PolicyArm> enabled = arms.stream().filter(PolicyArm::enabled).toList();
        if (enabled.isEmpty()) return Selection.baseline(context);

        PolicyArm greedy = greedyArm(enabled, context);
        boolean explore = enabled.size() > 1 && nextDouble() < epsilon;
        PolicyArm chosen = greedy;
        if (explore) {
            List<PolicyArm> alternatives = enabled.stream()
                    .filter(arm -> !arm.armId().equals(greedy.armId())).toList();
            chosen = alternatives.get(nextInt(alternatives.size()));
        }

        // Logged on every impression. It cannot be reconstructed later, and without it no unbiased
        // off-policy estimate of an alternative policy is possible.
        double propensity = chosen.armId().equals(greedy.armId())
                ? (1 - epsilon) + epsilon / enabled.size()
                : epsilon / enabled.size();

        if (shadowMode) {
            // Serve the baseline blend; record what the policy would have done. Zero user-visible
            // risk, and it produces exactly the log the replay harness needs. The propensity logged
            // is the shadow decision's, not the served one's — it describes the choice being
            // evaluated, which is what an off-policy estimate over shadow data needs.
            return new Selection(BASELINE_ARM, 1f, 1f, propensity, chosen.armId(), context);
        }
        return new Selection(chosen.armId(), chosen.wVector(), chosen.wLexical(),
                propensity, null, context);
    }

    /**
     * Query-only context features, all in [0, 1].
     * <ul>
     *   <li>identifier-ness — ticket ids, camelCase, paths: where the lexical leg wins</li>
     *   <li>question-ness — natural-language questions: where the vector leg wins</li>
     * </ul>
     */
    static float[] context(String query, Set<String> queryTerms) {
        float[] context = new float[DIMENSION];
        context[0] = 1f;
        if (query == null) return context;

        int termCount = queryTerms == null ? 0 : queryTerms.size();
        context[1] = Math.min(termCount / 8f, 1f);

        if (termCount > 0) {
            long identifiers = queryTerms.stream().filter(RankingPolicy::looksLikeIdentifier).count();
            context[2] = (float) identifiers / termCount;
        }

        String trimmed = query.trim().toLowerCase(java.util.Locale.ROOT);
        boolean question = trimmed.endsWith("?")
                || trimmed.startsWith("how") || trimmed.startsWith("why") || trimmed.startsWith("what")
                || trimmed.startsWith("when") || trimmed.startsWith("where") || trimmed.startsWith("which")
                || trimmed.startsWith("who") || trimmed.startsWith("can ") || trimmed.startsWith("does ");
        context[3] = question ? 1f : 0f;
        return context;
    }

    private static boolean looksLikeIdentifier(String term) {
        if (term.length() >= 12) return true;
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (Character.isDigit(c)) return true;
            if (i > 0 && Character.isUpperCase(c)) return true;
        }
        return false;
    }

    private PolicyArm greedyArm(List<PolicyArm> enabled, float[] context) {
        PolicyArm best = enabled.get(0);
        double bestScore = Double.NEGATIVE_INFINITY;
        for (PolicyArm arm : enabled) {
            double score = upperConfidenceBound(arm, context);
            // Strictly greater keeps declaration order as the tie-break, which is what makes an
            // untrained policy deterministically pick baseline.
            if (score > bestScore) {
                bestScore = score;
                best = arm;
            }
        }
        return best;
    }

    private double upperConfidenceBound(PolicyArm arm, float[] context) {
        double[][] inverse = Matrix.invert(arm.a());
        if (inverse == null) return 0;
        double[] theta = Matrix.multiply(inverse, arm.b());
        double mean = Matrix.dot(theta, context);
        double variance = Matrix.quadraticForm(inverse, context);
        return mean + alpha * Math.sqrt(Math.max(variance, 0));
    }

    // ── RewardListener ────────────────────────────────────────────────────────────────────────

    @Override
    public void onSettled(Impression impression, List<Feedback> feedback, Double reward) {
        if (!learningEnabled || reward == null) return;
        // A cache hit replays one earlier selection; counting it again would let a single
        // exploration decision be rewarded N times.
        if (impression.fromCache()) return;
        // MCP has no feedback surface, so an mcp-tagged row can only carry noise.
        if (LearningService.SURFACE_MCP.equals(impression.surface())) return;

        // In shadow mode the served arm was baseline but the policy "chose" the shadow arm — credit
        // the arm whose decision is being evaluated, which is what makes shadow data usable.
        String armId = impression.shadowArm() != null ? impression.shadowArm() : impression.armId();
        PolicyArm arm = arms.stream().filter(a -> a.armId().equals(armId)).findFirst().orElse(null);
        if (arm == null) return;

        float[] context = toContext(impression);
        double[][] a = Matrix.copy(arm.a());
        double[] b = arm.b().clone();
        for (int i = 0; i < DIMENSION; i++) {
            for (int j = 0; j < DIMENSION; j++) a[i][j] += (double) context[i] * context[j];
            b[i] += reward * context[i];
        }

        PolicyArm updated = new PolicyArm(arm.armId(), arm.wVector(), arm.wLexical(), a, b,
                arm.pulls() + 1, arm.rewardSum() + reward, arm.enabled());
        repository.save(updated);
        reload();
        applyGuardrail();
    }

    @Override
    public void reset() {
        repository.deleteAll();
        arms = List.of();
        ensureSeeded();
    }

    /**
     * Retires an arm that has had a fair trial and is measurably worse than baseline, so a bad blend
     * cannot keep being explored indefinitely. Re-enabled by a policy reset.
     */
    private void applyGuardrail() {
        PolicyArm baseline = arms.stream()
                .filter(arm -> arm.armId().equals(BASELINE_ARM)).findFirst().orElse(null);
        if (baseline == null || baseline.pulls() < GUARDRAIL_MIN_PULLS) return;

        for (PolicyArm arm : arms) {
            if (arm.armId().equals(BASELINE_ARM) || !arm.enabled()) continue;
            if (arm.pulls() < GUARDRAIL_MIN_PULLS) continue;
            if (arm.meanReward() < baseline.meanReward() - GUARDRAIL_MARGIN) {
                log.warn("Ranking arm '{}' disabled: mean reward {} vs baseline {} over {} pulls",
                        arm.armId(), round(arm.meanReward()), round(baseline.meanReward()), arm.pulls());
                repository.save(new PolicyArm(arm.armId(), arm.wVector(), arm.wLexical(),
                        arm.a(), arm.b(), arm.pulls(), arm.rewardSum(), false));
                reload();
            }
        }
    }

    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> described = new ArrayList<>();
        for (PolicyArm arm : arms) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("armId", arm.armId());
            entry.put("wVector", arm.wVector());
            entry.put("wLexical", arm.wLexical());
            entry.put("pulls", arm.pulls());
            entry.put("meanReward", round(arm.meanReward()));
            entry.put("enabled", arm.enabled());
            described.add(entry);
        }
        return described;
    }

    void ensureSeeded() {
        List<PolicyArm> existing = repository.findAll();
        Set<String> known = existing.stream().map(PolicyArm::armId).collect(java.util.stream.Collectors.toSet());
        for (PolicyArm seed : SEED_ARMS) {
            if (!known.contains(seed.armId())) repository.save(seed);
        }
        // Repair rows whose learned state failed to parse, rather than letting a corrupt matrix
        // silently make one arm un-selectable.
        for (PolicyArm arm : existing) {
            if (arm.a() == null || arm.b() == null) {
                repository.save(new PolicyArm(arm.armId(), arm.wVector(), arm.wLexical(),
                        Matrix.identity(DIMENSION), new double[DIMENSION],
                        arm.pulls(), arm.rewardSum(), arm.enabled()));
            }
        }
        reload();
    }

    private void reload() {
        arms = repository.findAll().stream()
                .map(arm -> arm.a() == null || arm.b() == null
                        ? new PolicyArm(arm.armId(), arm.wVector(), arm.wLexical(),
                                Matrix.identity(DIMENSION), new double[DIMENSION],
                                arm.pulls(), arm.rewardSum(), arm.enabled())
                        : arm)
                .toList();
    }

    private static float[] toContext(Impression impression) {
        float[] context = new float[DIMENSION];
        context[0] = 1f;
        List<Float> logged = impression.context();
        for (int i = 0; i < Math.min(DIMENSION, logged.size()); i++) {
            context[i] = logged.get(i) == null ? 0f : logged.get(i);
        }
        return context;
    }

    private static PolicyArm seed(String id, float wVector, float wLexical) {
        return new PolicyArm(id, wVector, wLexical,
                Matrix.identity(DIMENSION), new double[DIMENSION], 0, 0, true);
    }

    private double nextDouble() {
        return random != null ? random.nextDouble() : ThreadLocalRandom.current().nextDouble();
    }

    private int nextInt(int bound) {
        return random != null ? random.nextInt(bound) : ThreadLocalRandom.current().nextInt(bound);
    }

    /** Test seam: makes exploration deterministic. */
    void useRandom(Random seeded) {
        this.random = seeded;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** The 4×4 linear algebra LinUCB needs, and nothing more. */
    static final class Matrix {

        private Matrix() {
        }

        static double[][] identity(int n) {
            double[][] matrix = new double[n][n];
            for (int i = 0; i < n; i++) matrix[i][i] = 1.0;
            return matrix;
        }

        static double[][] copy(double[][] source) {
            double[][] target = new double[source.length][];
            for (int i = 0; i < source.length; i++) target[i] = source[i].clone();
            return target;
        }

        /** Gauss-Jordan with partial pivoting; null when singular. */
        static double[][] invert(double[][] matrix) {
            if (matrix == null || matrix.length == 0) return null;
            int n = matrix.length;
            double[][] a = copy(matrix);
            double[][] inverse = identity(n);

            for (int col = 0; col < n; col++) {
                int pivot = col;
                for (int row = col + 1; row < n; row++) {
                    if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) pivot = row;
                }
                if (Math.abs(a[pivot][col]) < 1e-12) return null;
                double[] swap = a[col]; a[col] = a[pivot]; a[pivot] = swap;
                swap = inverse[col]; inverse[col] = inverse[pivot]; inverse[pivot] = swap;

                double diagonal = a[col][col];
                for (int j = 0; j < n; j++) {
                    a[col][j] /= diagonal;
                    inverse[col][j] /= diagonal;
                }
                for (int row = 0; row < n; row++) {
                    if (row == col) continue;
                    double factor = a[row][col];
                    if (factor == 0) continue;
                    for (int j = 0; j < n; j++) {
                        a[row][j] -= factor * a[col][j];
                        inverse[row][j] -= factor * inverse[col][j];
                    }
                }
            }
            return inverse;
        }

        static double[] multiply(double[][] matrix, double[] vector) {
            double[] result = new double[matrix.length];
            for (int i = 0; i < matrix.length; i++) {
                double sum = 0;
                for (int j = 0; j < vector.length; j++) sum += matrix[i][j] * vector[j];
                result[i] = sum;
            }
            return result;
        }

        static double dot(double[] vector, float[] context) {
            double sum = 0;
            for (int i = 0; i < Math.min(vector.length, context.length); i++) {
                sum += vector[i] * context[i];
            }
            return sum;
        }

        /** x^T M x. */
        static double quadraticForm(double[][] matrix, float[] context) {
            double sum = 0;
            for (int i = 0; i < matrix.length; i++) {
                for (int j = 0; j < matrix[i].length; j++) {
                    sum += (double) context[i] * matrix[i][j] * context[j];
                }
            }
            return sum;
        }
    }
}
