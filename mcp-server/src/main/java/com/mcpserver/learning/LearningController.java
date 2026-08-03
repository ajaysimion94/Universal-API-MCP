package com.mcpserver.learning;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-out and controls for adaptive ranking: what has been learned, and how to undo it.
 * <p>
 * Reset and rebuild are the two halves of the "learned state is a derived cache" guarantee — reset
 * discards learned state while keeping the logs, and rebuild reconstructs it from those logs. A bad
 * policy is therefore never something you have to live with or hand-edit out of the database.
 */
@RestController
@RequestMapping("/api/search/learning")
public class LearningController {

    private static final int TOP_MEMORY_ENTRIES = 12;

    private final ImpressionRepository impressions;
    private final FeedbackRepository feedback;
    private final FeedbackMemory memory;
    private final RankingPolicy policy;
    private final RewardSettler settler;
    private final LearningWriter writer;
    private final boolean enabled;

    public LearningController(ImpressionRepository impressions,
                              FeedbackRepository feedback,
                              FeedbackMemory memory,
                              RankingPolicy policy,
                              RewardSettler settler,
                              LearningWriter writer,
                              @Value("${learning.enabled:true}") boolean enabled) {
        this.impressions = impressions;
        this.feedback = feedback;
        this.memory = memory;
        this.policy = policy;
        this.settler = settler;
        this.writer = writer;
        this.enabled = enabled;
    }

    public static class ScopeRequest {
        public String scope;
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("banditEnabled", policy.isBanditEnabled());
        status.put("shadowMode", policy.isShadowMode());
        status.put("impressions", impressions.summaryCounts(Instant.now().minus(1, ChronoUnit.DAYS)));
        status.put("feedback", feedback.countsBySignal());
        status.put("latency", impressions.latencyByArm());
        status.put("arms", policy.describe());
        status.put("memory", Map.of(
                "entries", memory.size(),
                "topEntries", memory.describe(TOP_MEMORY_ENTRIES)));
        status.put("droppedWrites", writer.droppedWrites());
        return status;
    }

    /**
     * Clears learned state only. {@code search_impressions} and {@code search_feedback} are never
     * touched — they are the ground truth {@link #rebuild()} replays from.
     */
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestBody(required = false) ScopeRequest request) {
        String scope = request == null || request.scope == null || request.scope.isBlank()
                ? "all"
                : request.scope.trim().toLowerCase(Locale.ROOT);
        List<String> valid = List.of("memory", "policy", "all");
        if (!valid.contains(scope)) {
            throw new IllegalArgumentException("scope must be one of " + valid);
        }

        writer.submitDeferred("reset-" + scope, () -> {
            if (scope.equals("memory") || scope.equals("all")) memory.reset();
            if (scope.equals("policy") || scope.equals("all")) policy.reset();
        });
        return Map.of("reset", scope, "logsRetained", true);
    }

    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        settler.rebuildAll();
        return Map.of("rebuilding", true);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }
}
