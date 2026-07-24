package com.mcpserver.workflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimiter {
    private final double maxTokens;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(@Value("${tools.rate-limit-per-minute:10}") double rateLimitPerMinute) {
        this.maxTokens = rateLimitPerMinute;
    }

    public void checkToolLimit(String toolId) {
        if (!getBucket(toolId).consume()) {
            throw new IllegalStateException("Rate limit exceeded for tool " + toolId + ". Retry-After 60 seconds.");
        }
    }

    public void checkClientLimit(String clientId) {
        if (!getBucket(clientId).consume()) {
            throw new IllegalStateException("Rate limit exceeded for client " + clientId + ". Retry-After 60 seconds.");
        }
    }

    private TokenBucket getBucket(String key) {
        return buckets.computeIfAbsent(key, k -> new TokenBucket(maxTokens, maxTokens / 60000.0));
    }

    private static class TokenBucket {
        private double tokens;
        private long lastRefill;
        private final double maxTokens;
        private final double refillRatePerMs;

        public TokenBucket(double maxTokens, double refillRatePerMs) {
            this.tokens = maxTokens;
            this.maxTokens = maxTokens;
            this.refillRatePerMs = refillRatePerMs;
            this.lastRefill = System.currentTimeMillis();
        }

        public synchronized boolean consume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefill;
            if (elapsed > 0) {
                tokens = Math.min(maxTokens, tokens + elapsed * refillRatePerMs);
                lastRefill = now;
            }
        }
    }
}
