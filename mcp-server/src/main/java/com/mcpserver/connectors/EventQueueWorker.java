package com.mcpserver.connectors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Single background worker processing the {@code ingestion_events} durable queue — the SQLite-based
 * replacement for the Postgres-outbox design in the original plan (see DECISIONS.md). Polls for
 * PENDING rows rather than blocking on a signal: simplest correct option given events are produced
 * by rare webhook hits, not a hot path, and the 3-second webhook-ack SLA is satisfied at intake
 * time by {@code ConnectionController} inserting the row and returning immediately — this worker's
 * pickup latency doesn't matter for that SLA.
 *
 * On startup, any row left PROCESSING by a crash mid-handling is reset to PENDING so it's retried
 * — this is what makes "kill workers mid-burst, restart → no events lost" true.
 */
@Component
public class EventQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(EventQueueWorker.class);
    private static final long POLL_INTERVAL_MS = 2000;
    private static final int MAX_ATTEMPTS = 5;

    private final IngestionEventRepository eventRepository;
    private final ConnectionRepository connectionRepository;
    private final Map<ConnectionType, SourceConnector> connectorsByType;

    private volatile boolean running = true;
    private Thread workerThread;

    public EventQueueWorker(IngestionEventRepository eventRepository,
                             ConnectionRepository connectionRepository,
                             List<SourceConnector> connectors) {
        this.eventRepository = eventRepository;
        this.connectionRepository = connectionRepository;
        this.connectorsByType = connectors.stream()
                .collect(Collectors.toMap(SourceConnector::type, Function.identity()));
    }

    @PostConstruct
    void start() {
        try {
            int requeued = eventRepository.findPendingOrProcessing().size();
            eventRepository.resetProcessingToPending();
            if (requeued > 0) {
                log.info("EventQueueWorker: {} event(s) pending/in-flight at startup, requeued", requeued);
            }
        } catch (Exception e) {
            log.warn("EventQueueWorker: schema not yet ready — {} (will retry in loop)", e.getMessage());
        }
        workerThread = new Thread(this::loop, "ingestion-event-worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (workerThread != null) workerThread.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                eventRepository.claimNextPending().ifPresentOrElse(this::process, this::sleep);
            } catch (Exception e) {
                log.warn("EventQueueWorker loop error: {}", e.getMessage());
                sleep();
            }
        }
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void process(IngestionEvent event) {
        try {
            Connection connection = connectionRepository.findById(event.connectionId()).orElse(null);
            if (connection == null) {
                log.info("Dropping event {} — its connection no longer exists", event.id());
                eventRepository.markDone(event.id());
                return;
            }
            SourceConnector connector = connectorsByType.get(connection.type());
            if (connector == null) {
                throw new IllegalStateException("No connector implementation registered for type: " + connection.type());
            }
            switch (event.eventType()) {
                case WEBHOOK -> connector.handleWebhookPayload(connection, event.payload());
                case DELTA_POLL -> log.debug("DELTA_POLL events are not queued yet — pollDelta ingests synchronously");
            }
            eventRepository.markDone(event.id());
        } catch (Exception e) {
            int attempts = event.attempts() + 1;
            log.warn("Event {} failed (attempt {}/{}): {}", event.id(), attempts, MAX_ATTEMPTS, e.getMessage());
            eventRepository.markFailed(event.id(), e.getMessage(), attempts, MAX_ATTEMPTS);
        }
    }
}
