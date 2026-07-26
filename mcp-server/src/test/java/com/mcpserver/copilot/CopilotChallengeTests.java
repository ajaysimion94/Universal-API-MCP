package com.mcpserver.copilot;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CopilotChallengeTests {

    @Test
    void hashcashSolutionSatisfiesDifficulty() throws Exception {
        String seed = "copilot-test-seed";
        int difficulty = 8; // one full zero byte — fast to solve, easy to verify

        String nonce = CopilotChallenge.solveHashcash(seed + ":" + difficulty);

        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((seed + nonce).getBytes(StandardCharsets.UTF_8));
        assertThat(digest[0]).as("leading byte must be zero for difficulty 8").isEqualTo((byte) 0);
    }

    @Test
    void hashcashIsDeterministic() {
        String parameter = "deterministic-seed:4";
        assertThat(CopilotChallenge.solveHashcash(parameter))
                .isEqualTo(CopilotChallenge.solveHashcash(parameter));
    }

    @Test
    void hashcashRefusesToRunPastTheIterationCap() {
        // Difficulty 31 needs ~2^31 iterations on average; a cap of 1 000 must fail fast.
        assertThatThrownBy(() -> CopilotChallenge.solveHashcash("impossible-seed:31", 1_000))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("exceeded");
    }

    @Test
    void copilotChallengeMatchesTheFormula() {
        // a=10 → (10^3/100 + 10*25) % 22 = (10 + 250) % 22 = 260 % 22 = 18
        assertThat(CopilotChallenge.solveCopilotChallenge("10")).isEqualTo("18");
        // a=0 → 0
        assertThat(CopilotChallenge.solveCopilotChallenge("0")).isEqualTo("0");
    }

    @Test
    void copilotChallengeStaysInRange() {
        for (int a = 1; a <= 200; a++) {
            int result = Integer.parseInt(CopilotChallenge.solveCopilotChallenge(String.valueOf(a)));
            assertThat(result).isBetween(0, 22);
        }
    }
}
