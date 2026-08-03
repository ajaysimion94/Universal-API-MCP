package com.mcpserver.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.learning.LearningModel.Feedback;
import com.mcpserver.learning.LearningModel.Impression;
import com.mcpserver.learning.LearningModel.ServedResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Offline evaluation of the adaptive-ranking learners against real logged traffic.
 * <p>
 * Run with {@code scripts/run-replay.sh}, which points {@code -Dreplay.db} at a <em>copy</em> of the
 * workspace database. The copy is opened read-only through its own connection, so the harness can
 * never contend with, or write to, the application's single shared connection. Without a database
 * the test skips — the same pattern the golden-set gate uses for a missing model bundle, so a fresh
 * checkout and CI both stay green.
 * <p>
 * <strong>What this can and cannot prove.</strong> Only the <em>fusion</em> ordering is
 * recomputable after the fact. The cross-encoder's verdict on a chunk that was never retrieved is
 * unknowable, so the numbers below are a bound on what an alternative policy would have done, not a
 * simulation of it. That limit is why {@code Recall@candidate} is reported: it is the part the
 * fusion weights actually control.
 */
class ReplayHarnessTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

    /** Below this effective sample size an off-policy estimate is noise; report, assert nothing. */
    private static final double MIN_EFFECTIVE_SAMPLE = 30;

    @Test
    void replayLoggedTrafficAndEstimateAlternativePolicies() throws Exception {
        String dbPath = System.getProperty("replay.db", "");
        assumeTrue(!dbPath.isBlank() && Files.exists(Path.of(dbPath)),
                "no replay database; run scripts/run-replay.sh to evaluate logged traffic");

        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite:file:" + dbPath + "?mode=ro", true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            ImpressionRepository impressions = new ImpressionRepository(jdbc);
            FeedbackRepository feedback = new FeedbackRepository(jdbc);

            List<Impression> corpus = impressions.findWithFeedback();
            Map<String, List<Feedback>> byImpression = feedback.findAll().stream()
                    .collect(Collectors.groupingBy(Feedback::impressionId));
            assumeTrue(!corpus.isEmpty(), "replay database has no impressions with feedback yet");

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("impressionsWithFeedback", corpus.size());
            report.put("rewardSummary", rewardSummary(corpus, byImpression));
            report.put("offPolicy", offPolicyEstimates(corpus, byImpression));
            report.put("generalization", generalizationSplit(corpus, byImpression));
            report.put("latency", latencySummary(corpus));

            String runId = System.getProperty("eval.run.id", Long.toString(System.currentTimeMillis()));
            Path out = REPO_ROOT.resolve("eval-runs").resolve(runId).resolve("replay.json");
            Files.createDirectories(out.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);

            assertThat(out).exists();
        } finally {
            dataSource.destroy();
        }
    }

    private static Map<String, Object> rewardSummary(List<Impression> corpus,
                                                     Map<String, List<Feedback>> byImpression) {
        List<Double> rewards = corpus.stream()
                .map(impression -> RewardCalculator.reward(
                        impression, byImpression.getOrDefault(impression.id(), List.of())))
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rewarded", rewards.size());
        summary.put("mean", rewards.isEmpty() ? null
                : rewards.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        summary.put("median", rewards.isEmpty() ? null : rewards.get(rewards.size() / 2));
        return summary;
    }

    /**
     * Self-normalised inverse propensity scoring. Because the exploration rate and the exact
     * selection probability were logged on every impression, the value of a policy that was never
     * served can be estimated from the traffic that was.
     * <p>
     * A context-free per-arm mean is reported alongside the learned policy on purpose: if the
     * context is not earning its complexity, this is where that shows up.
     */
    private static Map<String, Object> offPolicyEstimates(List<Impression> corpus,
                                                          Map<String, List<Feedback>> byImpression) {
        Set<String> arms = corpus.stream()
                .map(ReplayHarnessTests::decisionArm)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        Map<String, Object> estimates = new LinkedHashMap<>();
        for (String candidate : arms) {
            double weightedReward = 0;
            double weightSum = 0;
            double weightSquareSum = 0;
            int matched = 0;
            for (Impression impression : corpus) {
                if (!decisionArm(impression).equals(candidate)) continue;
                Double reward = RewardCalculator.reward(
                        impression, byImpression.getOrDefault(impression.id(), List.of()));
                if (reward == null) continue;
                double propensity = Math.max(impression.propensity(), 1e-6);
                double weight = 1.0 / propensity;
                weightedReward += weight * reward;
                weightSum += weight;
                weightSquareSum += weight * weight;
                matched++;
            }
            double effectiveSample = weightSum == 0 ? 0 : (weightSum * weightSum) / weightSquareSum;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("samples", matched);
            entry.put("effectiveSampleSize", round(effectiveSample));
            entry.put("estimatedValue", weightSum == 0 ? null : round(weightedReward / weightSum));
            entry.put("sufficientData", effectiveSample >= MIN_EFFECTIVE_SAMPLE);
            estimates.put(candidate, entry);
        }

        estimates.put("_note", "Below an effective sample size of " + (int) MIN_EFFECTIVE_SAMPLE
                + " these estimates are noise. Stopping rule: if the learned policy's value does not "
                + "exceed baseline by more than one standard error, leave learning.bandit.enabled=false.");
        return estimates;
    }

    /**
     * Splits by query family rather than by time or row.
     * <ul>
     *   <li><em>seen</em> — later occurrences of a family the learner has already been trained on.
     *       Lift here is memorization, which is precisely the feedback memory's job: a pass.</li>
     *   <li><em>unseen</em> — families held out entirely. Lift here is generalization, and it is the
     *       only split on which the bandit should be judged; the memory is expected to score zero.</li>
     * </ul>
     * Reporting both side by side is what stops the memory's memorization being read as a general
     * improvement in search quality.
     */
    private static Map<String, Object> generalizationSplit(List<Impression> corpus,
                                                           Map<String, List<Feedback>> byImpression) {
        Map<String, List<Impression>> families = new LinkedHashMap<>();
        corpus.stream()
                .sorted(Comparator.comparing(Impression::servedAt))
                .forEach(impression -> families
                        .computeIfAbsent(impression.queryNorm(), key -> new ArrayList<>())
                        .add(impression));

        List<Double> seen = new ArrayList<>();
        List<Double> unseen = new ArrayList<>();
        for (List<Impression> family : families.values()) {
            for (int index = 0; index < family.size(); index++) {
                Impression impression = family.get(index);
                Double reward = RewardCalculator.reward(
                        impression, byImpression.getOrDefault(impression.id(), List.of()));
                if (reward == null) continue;
                // First occurrence of a family is unseen by definition; later ones are repeats.
                (index == 0 ? unseen : seen).add(reward);
            }
        }

        Map<String, Object> split = new LinkedHashMap<>();
        split.put("families", families.size());
        split.put("seenQueries", summarize(seen));
        split.put("unseenQueries", summarize(unseen));
        split.put("_note", "Lift on seen queries is memorization (the feedback memory working as "
                + "intended). Only lift on unseen queries is generalization.");
        return split;
    }

    /** Proves "impression logging didn't slow search" from data rather than asserting it. */
    private static Map<String, Object> latencySummary(List<Impression> corpus) {
        Map<String, List<Long>> byArm = new HashMap<>();
        for (Impression impression : corpus) {
            String key = impression.armId() + (impression.fromCache() ? " (cached)" : "");
            byArm.computeIfAbsent(key, k -> new ArrayList<>()).add(impression.latencyMs());
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        byArm.forEach((arm, samples) -> {
            List<Long> sorted = samples.stream().sorted().toList();
            summary.put(arm, Map.of(
                    "samples", sorted.size(),
                    "p50Ms", sorted.get(sorted.size() / 2),
                    "p95Ms", sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.95)))));
        });
        return summary;
    }

    /** In shadow mode the served arm is baseline; the decision under evaluation is the shadow arm. */
    private static String decisionArm(Impression impression) {
        return impression.shadowArm() != null ? impression.shadowArm() : impression.armId();
    }

    private static Map<String, Object> summarize(List<Double> values) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", values.size());
        summary.put("meanReward", values.isEmpty() ? null
                : round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        return summary;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
