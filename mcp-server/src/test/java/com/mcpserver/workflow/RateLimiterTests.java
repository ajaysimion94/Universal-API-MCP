package com.mcpserver.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
public class RateLimiterTests {

    @Autowired
    private RateLimiter rateLimiter;

    @Test
    void testRateLimiterWithinLimits() {
        // Pinned limit is 10/min. We should be able to call 5 times successfully
        assertThatCode(() -> {
            for (int i = 0; i < 5; i++) {
                rateLimiter.checkToolLimit("some-tool-id");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void testRateLimiterExceeded() {
        // Exceeding the limit should throw IllegalStateException
        assertThatThrownBy(() -> {
            for (int i = 0; i < 20; i++) {
                rateLimiter.checkToolLimit("another-tool-id");
            }
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Rate limit exceeded");
    }
}
