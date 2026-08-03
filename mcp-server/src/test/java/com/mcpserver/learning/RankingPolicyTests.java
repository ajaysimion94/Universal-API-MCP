package com.mcpserver.learning;

import com.mcpserver.learning.LearningModel.Feedback;
import com.mcpserver.learning.LearningModel.Impression;
import com.mcpserver.learning.LearningModel.ServedResult;
import com.mcpserver.learning.LearningModel.Signal;
import com.mcpserver.rag.retrieval.TextSignals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RankingPolicyTests {

    @Autowired
    PolicyArmRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private RankingPolicy policy(boolean banditEnabled, boolean shadowMode, double epsilon) {
        RankingPolicy policy = new RankingPolicy(repository, true, banditEnabled, shadowMode, epsilon, 0.35);
        policy.ensureSeeded();
        return policy;
    }

    private static Set<String> terms(String query) {
        return TextSignals.terms(query);
    }

    /**
     * The claim the whole rollout rests on: with the bandit disabled, ranking is the unweighted
     * (1, 1) fusion — i.e. byte-for-byte what a build without this subsystem serves.
     */
    @Test
    void withTheBanditDisabledEverySearchGetsTheBaselineBlend() {
        RankingPolicy policy = policy(false, false, 0.10);

        for (String query : List.of("how do I roll back a deployment", "PROJ-1234", "acl tags")) {
            RankingPolicy.Selection selection = policy.select(query, terms(query));
            assertThat(selection.armId()).isEqualTo("baseline");
            assertThat(selection.wVector()).isEqualTo(1f);
            assertThat(selection.wLexical()).isEqualTo(1f);
            assertThat(selection.shadowArm()).isNull();
        }
    }

    /**
     * Cold start must be deterministic too: an untrained LinUCB has identical estimates and identical
     * confidence bonuses for every arm, so the tie-break (declaration order) must land on baseline.
     */
    @Test
    void anUntrainedPolicyExploitsToBaseline() {
        // epsilon 0 removes exploration, isolating the greedy path.
        RankingPolicy policy = policy(true, false, 0.0);

        RankingPolicy.Selection selection = policy.select("deployment rollback", terms("deployment rollback"));

        assertThat(selection.armId()).isEqualTo("baseline");
        assertThat(selection.propensity()).isEqualTo(1.0);
    }

    @Test
    void explorationServesANonGreedyArmAndLogsItsPropensity() {
        RankingPolicy policy = policy(true, false, 1.0);   // always explore
        policy.useRandom(new Random(7));

        RankingPolicy.Selection selection = policy.select("deployment rollback", terms("deployment rollback"));

        assertThat(selection.armId()).isNotEqualTo("baseline");
        // epsilon/|A| with five arms.
        assertThat(selection.propensity()).isEqualTo(1.0 / 5);
        assertThat(selection.wVector() + selection.wLexical()).isEqualTo(2f);
    }

    /** Shadow mode is the zero-risk dry run: it records a decision without acting on it. */
    @Test
    void shadowModeRecordsTheChoiceButStillServesBaseline() {
        RankingPolicy policy = policy(false, true, 1.0);
        policy.useRandom(new Random(3));

        RankingPolicy.Selection selection = policy.select("deployment rollback", terms("deployment rollback"));

        assertThat(selection.armId()).isEqualTo("baseline");
        assertThat(selection.wVector()).isEqualTo(1f);
        assertThat(selection.wLexical()).isEqualTo(1f);
        assertThat(selection.shadowArm()).isNotNull().isNotEqualTo("baseline");
    }

    @Test
    void contextSeparatesIdentifierQueriesFromNaturalLanguageQuestions() {
        float[] identifier = RankingPolicy.context("PROJ-1234 build9 failure", terms("PROJ-1234 build9 failure"));
        float[] question = RankingPolicy.context("how do I roll back a deployment?",
                terms("how do I roll back a deployment?"));

        assertThat(identifier[0]).isEqualTo(1f);          // bias
        // "1234" and "build9" read as identifiers; "proj" and "failure" do not — 2 of 4.
        assertThat(identifier[2]).isEqualTo(0.5f);
        assertThat(identifier[3]).isZero();               // not a question
        assertThat(question[2]).isZero();                 // no identifier-shaped terms at all
        assertThat(question[3]).isEqualTo(1f);
        assertThat(identifier[2]).isGreaterThan(question[2]);
    }

    @Test
    void rewardsAccumulateOntoTheServedArm() {
        RankingPolicy policy = policy(true, false, 0.0);

        policy.onSettled(impression("vec-lean", null, false, "web"), someFeedback(), 0.9);
        policy.onSettled(impression("vec-lean", null, false, "web"), someFeedback(), 0.7);

        var arm = policy.describe().stream()
                .filter(entry -> entry.get("armId").equals("vec-lean")).findFirst().orElseThrow();
        assertThat(arm.get("pulls")).isEqualTo(2);
        assertThat((double) arm.get("meanReward")).isEqualTo(0.8);
    }

    /** One exploration decision must not be credited N times because the response was cached. */
    @Test
    void cachedImpressionsAreNotCountedAsPulls() {
        RankingPolicy policy = policy(true, false, 0.0);

        policy.onSettled(impression("vec-lean", null, true, "web"), someFeedback(), 0.9);

        assertThat(pulls(policy, "vec-lean")).isZero();
    }

    /** MCP cannot send feedback, so an mcp-tagged row can only carry noise. */
    @Test
    void mcpImpressionsDoNotTrainThePolicy() {
        RankingPolicy policy = policy(true, false, 0.0);

        policy.onSettled(impression("vec-lean", null, false, "mcp"), someFeedback(), 0.9);

        assertThat(pulls(policy, "vec-lean")).isZero();
    }

    /** Shadow data is only useful if it credits the arm whose decision is under evaluation. */
    @Test
    void shadowImpressionsCreditTheShadowArmNotTheServedOne() {
        RankingPolicy policy = policy(false, true, 0.0);

        policy.onSettled(impression("baseline", "lex-strong", false, "web"), someFeedback(), 0.8);

        assertThat(pulls(policy, "lex-strong")).isEqualTo(1);
        assertThat(pulls(policy, "baseline")).isZero();
    }

    /** A persistently bad blend retires itself rather than being explored forever. */
    @Test
    void aPersistentlyUnderperformingArmIsDisabled() {
        RankingPolicy policy = policy(true, false, 0.0);

        for (int i = 0; i < 25; i++) {
            policy.onSettled(impression("baseline", null, false, "web"), someFeedback(), 0.9);
            policy.onSettled(impression("lex-strong", null, false, "web"), someFeedback(), 0.2);
        }

        var arm = policy.describe().stream()
                .filter(entry -> entry.get("armId").equals("lex-strong")).findFirst().orElseThrow();
        assertThat(arm.get("enabled")).isEqualTo(false);
        assertThat(policy.select("anything", terms("anything")).armId()).isNotEqualTo("lex-strong");
    }

    @Test
    void resetReturnsEveryArmToTheUntrainedPrior() {
        RankingPolicy policy = policy(true, false, 0.0);
        policy.onSettled(impression("vec-lean", null, false, "web"), someFeedback(), 0.9);
        assertThat(pulls(policy, "vec-lean")).isEqualTo(1);

        policy.reset();

        assertThat(policy.describe()).hasSize(5)
                .allSatisfy(arm -> assertThat(arm.get("pulls")).isEqualTo(0));
        assertThat(policy.select("deployment", terms("deployment")).armId()).isEqualTo("baseline");
    }

    @Test
    void matrixInverseRoundTripsToIdentity() {
        double[][] matrix = {{4, 1, 0, 0}, {1, 3, 1, 0}, {0, 1, 2, 1}, {0, 0, 1, 5}};

        double[][] inverse = RankingPolicy.Matrix.invert(matrix);

        assertThat(inverse).isNotNull();
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double sum = 0;
                for (int k = 0; k < 4; k++) sum += matrix[i][k] * inverse[k][j];
                assertThat(sum).isCloseTo(i == j ? 1 : 0, org.assertj.core.data.Offset.offset(1e-9));
            }
        }
    }

    @Test
    void singularMatricesAreRejectedRatherThanReturningGarbage() {
        assertThat(RankingPolicy.Matrix.invert(new double[][]{{1, 2}, {2, 4}})).isNull();
    }

    private static int pulls(RankingPolicy policy, String armId) {
        return (int) policy.describe().stream()
                .filter(entry -> entry.get("armId").equals(armId)).findFirst().orElseThrow().get("pulls");
    }

    private static Impression impression(String armId, String shadowArm, boolean fromCache, String surface) {
        return new Impression(UUID.randomUUID().toString(), "deployment rollback",
                "deployment rollback", surface, 10, false, false, fromCache, armId, 0.9, shadowArm,
                List.of(1f, 0.25f, 0f, 0f), List.of(new ServedResult("chunk-a", 1, 0.5f)),
                0, 20, Instant.now(), null, null);
    }

    private static List<Feedback> someFeedback() {
        return List.of(new Feedback(0, "imp", "chunk-a", 1, Signal.RATING, 1f, "web-user", Instant.now()));
    }
}
