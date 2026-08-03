package com.mcpserver.learning;

import com.mcpserver.learning.LearningModel.Feedback;
import com.mcpserver.learning.LearningModel.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Intake for user signals on search results.
 * <p>
 * The handler validates, hands the batch to {@link LearningWriter}, and returns — it performs no
 * database access on the request thread, so a burst of implicit signals from an expanding UI cannot
 * slow the page down.
 */
@RestController
@RequestMapping("/api/search/feedback")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);
    private static final int MAX_EVENTS = 50;

    private final LearningWriter writer;
    private final ImpressionRepository impressions;
    private final RewardSettler settler;
    private final boolean enabled;

    public FeedbackController(LearningWriter writer,
                              ImpressionRepository impressions,
                              RewardSettler settler,
                              @org.springframework.beans.factory.annotation.Value("${learning.enabled:true}")
                              boolean enabled) {
        this.writer = writer;
        this.impressions = impressions;
        this.settler = settler;
        this.enabled = enabled;
    }

    /** Public-field request body, matching {@code ReportController.AnalyzeRequest} and friends. */
    public static class FeedbackRequest {
        public String impressionId;
        public List<EventBody> events;
    }

    public static class EventBody {
        public String chunkId;
        public Integer rank;
        public String signal;
        public Double value;
    }

    @PostMapping
    public Map<String, Object> submit(@RequestBody FeedbackRequest request,
                                      @RequestHeader(value = "X-Actor", defaultValue = "web-user")
                                      String actor) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("learning", enabled);
        if (!enabled) {
            response.put("accepted", 0);
            response.put("ignored", "learning disabled");
            return response;
        }

        String impressionId = require(request == null ? null : request.impressionId, "impressionId");
        List<EventBody> events = request.events;
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("events must contain at least one signal");
        }
        if (events.size() > MAX_EVENTS) {
            throw new IllegalArgumentException("events must contain at most " + MAX_EVENTS + " signals");
        }

        // An unknown impression is answered 200, never 4xx. A turn kept in localStorage outlives the
        // row it points at (a reset, a prune, a fresh database), and a stale vote must not raise an
        // error banner in a page the user is quietly reading.
        if (impressions.findById(impressionId).isEmpty()) {
            log.debug("Feedback for unknown impression {} ignored", impressionId);
            response.put("accepted", 0);
            response.put("ignored", "unknown impression");
            return response;
        }

        Instant now = Instant.now();
        List<Feedback> parsed = new ArrayList<>(events.size());
        boolean explicit = false;
        for (EventBody event : events) {
            Signal signal = parseSignal(event.signal);
            float value = signal == Signal.RATING
                    ? clampRating(event.value)
                    : signal.implicitValue();
            if (signal == Signal.RATING) explicit = true;
            parsed.add(new Feedback(
                    0,
                    impressionId,
                    event.chunkId == null ? "" : event.chunkId,
                    event.rank == null ? 0 : Math.max(0, event.rank),
                    signal,
                    value,
                    actor,
                    now));
        }

        writer.recordFeedback(parsed);
        // An explicit thumb will not get better by waiting out the collection window.
        if (explicit) settler.settleNow(impressionId);

        response.put("accepted", parsed.size());
        return response;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static Signal parseSignal(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("signal is required");
        }
        try {
            return Signal.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown signal '" + raw + "'");
        }
    }

    /** A rating is +1, -1, or 0 (cleared). Anything else is a client bug, so it clamps rather than throws. */
    private static float clampRating(Double value) {
        if (value == null) return 0f;
        return (float) Math.signum(value);
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
