package com.mcpserver.copilot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Solves the proof-of-work challenges the Copilot WebSocket issues before accepting a
 * {@code send} frame. Two methods are known: {@code hashcash} (find a nonce whose
 * SHA-256 has N leading zero bits) and {@code copilot} (a fixed arithmetic formula).
 */
public class CopilotChallenge {

    /**
     * Safety bound on hashcash work. Difficulty rises with server load; a difficulty that
     * would need more iterations than this almost certainly indicates a changed protocol
     * (or an abusive endpoint), so we fail fast instead of spinning forever.
     */
    static final long MAX_HASHCASH_ITERATIONS = 50_000_000L;

    public static String solveHashcash(String parameter) {
        return solveHashcash(parameter, MAX_HASHCASH_ITERATIONS);
    }

    static String solveHashcash(String parameter, long maxIterations) {
        int colon = parameter.lastIndexOf(':');
        String seed = parameter.substring(0, colon);
        int difficulty = Integer.parseInt(parameter.substring(colon + 1));
        long n = 0;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            while (n <= maxIterations) {
                byte[] input = (seed + n).getBytes(StandardCharsets.UTF_8);
                byte[] digest = md.digest(input);
                if (hashcashOk(digest, difficulty)) {
                    return String.valueOf(n);
                }
                n++;
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
        throw new RuntimeException("Hashcash challenge exceeded " + maxIterations
                + " iterations (difficulty " + difficulty + ") — refusing to continue");
    }

    private static boolean hashcashOk(byte[] digest, int difficulty) {
        int fullBytes = difficulty / 8;
        int remainingBits = difficulty % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (digest[i] != 0) return false;
        }
        if (remainingBits > 0) {
            int mask = (255 << (8 - remainingBits)) & 0xFF;
            if ((digest[fullBytes] & mask) != 0) return false;
        }
        return true;
    }

    public static String solveCopilotChallenge(String parameter) {
        double a = Double.parseDouble(parameter);
        double result = ((Math.pow(a, 3) / 100.0) + a * 25) % 22;
        return String.valueOf((int) Math.round(result));
    }

    private CopilotChallenge() {}
}
