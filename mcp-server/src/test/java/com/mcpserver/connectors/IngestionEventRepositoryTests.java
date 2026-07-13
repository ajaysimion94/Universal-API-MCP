package com.mcpserver.connectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the crash-recovery mechanism EventQueueWorker relies on at startup: a row left
 * PROCESSING by a crash mid-handling must come back as claimable PENDING work, not be lost. This
 * is what makes "kill workers mid-burst, restart → no events lost" (docs/plan.md Phase 2) true
 * without a second datastore or SKIP LOCKED-style locking.
 *
 * <p>The real {@link EventQueueWorker} is mocked out here — it's a live background thread that
 * would otherwise race this test's manual claim/reset calls for the same rows.
 */
@SpringBootTest
class IngestionEventRepositoryTests {

    @Autowired
    private IngestionEventRepository eventRepository;

    @MockBean
    private EventQueueWorker eventQueueWorker;

    @Test
    void eventLeftProcessingByACrashIsRequeuedAndReclaimableAfterRestart() {
        String connectionId = "conn-" + UUID.randomUUID();
        IngestionEvent event = eventRepository.insert(
                IngestionEvent.create(connectionId, EventType.WEBHOOK, "page-1", "{\"page\":{\"id\":\"page-1\"}}"));

        // Simulates the worker claiming the event (flips PENDING -> PROCESSING) and then the
        // process crashing before markDone/markFailed ever runs.
        IngestionEvent claimed = eventRepository.claimNextPending().orElseThrow();
        assertThat(claimed.id()).isEqualTo(event.id());
        assertThat(eventRepository.findById(event.id()).orElseThrow().status()).isEqualTo(EventStatus.PROCESSING);

        // Simulates EventQueueWorker.start() running again on restart.
        List<IngestionEvent> strandedAtStartup = eventRepository.findPendingOrProcessing();
        assertThat(strandedAtStartup).extracting(IngestionEvent::id).contains(event.id());
        eventRepository.resetProcessingToPending();

        assertThat(eventRepository.findById(event.id()).orElseThrow().status()).isEqualTo(EventStatus.PENDING);

        // The event is claimable again — nothing was lost.
        IngestionEvent reclaimed = eventRepository.claimNextPending().orElseThrow();
        assertThat(reclaimed.id()).isEqualTo(event.id());
        assertThat(reclaimed.payload()).isEqualTo(event.payload());

        eventRepository.markDone(event.id());
        assertThat(eventRepository.findById(event.id()).orElseThrow().status()).isEqualTo(EventStatus.DONE);
    }

    @Test
    void deadLettersAfterMaxAttempts() {
        String connectionId = "conn-" + UUID.randomUUID();
        IngestionEvent event = eventRepository.insert(
                IngestionEvent.create(connectionId, EventType.WEBHOOK, "page-2", "{}"));
        eventRepository.claimNextPending();

        eventRepository.markFailed(event.id(), "boom", 1, 5);
        assertThat(eventRepository.findById(event.id()).orElseThrow().status()).isEqualTo(EventStatus.PENDING);

        eventRepository.markFailed(event.id(), "boom again", 5, 5);
        assertThat(eventRepository.findById(event.id()).orElseThrow().status()).isEqualTo(EventStatus.DEAD_LETTER);
    }
}
