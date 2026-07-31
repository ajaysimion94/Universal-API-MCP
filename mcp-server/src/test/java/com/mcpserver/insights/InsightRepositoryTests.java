package com.mcpserver.insights;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRUD coverage against the real embedded SQLite store — previously only
 * {@link InsightDocumentParserTests} exercised this package, leaving the actual persistence path
 * (used by every save/open/delete in the Insights workspace) unverified. Test data is unique per
 * run (UUID-suffixed ids/names) per the project's SQLite test convention: the database is not
 * reset between runs.
 */
@SpringBootTest
class InsightRepositoryTests {

    @Autowired
    private InsightRepository insightRepository;

    @Test
    void insertThenFindByIdRoundTripsEveryField() {
        String id = "insight-" + UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        SavedInsight insight = new SavedInsight(id, "Original name", "Original description",
                "let rows = request \"Ping\";", "conn-1", now, now);

        insightRepository.insert(insight);

        SavedInsight found = insightRepository.findById(id).orElseThrow();
        assertThat(found.name()).isEqualTo("Original name");
        assertThat(found.description()).isEqualTo("Original description");
        assertThat(found.source()).isEqualTo("let rows = request \"Ping\";");
        assertThat(found.connectionId()).isEqualTo("conn-1");
        assertThat(found.createdAt()).isEqualTo(now);
        assertThat(found.updatedAt()).isEqualTo(now);
        assertThat(found.lastRun()).as("a fresh insight has never been run").isNull();
        assertThat(found.lastRunAt()).isNull();
    }

    // ── run snapshot ─────────────────────────────────────────────────────────────

    @Test
    void lastRunSnapshotRoundTripsThroughFindById() {
        String id = insert("Snapshot");
        Instant ranAt = Instant.parse("2026-05-01T10:00:00Z");

        insightRepository.updateLastRun(id, "{\"datasets\":{\"orders\":{\"rows\":[{\"id\":1}]}}}", ranAt);

        SavedInsight found = insightRepository.findById(id).orElseThrow();
        assertThat(found.lastRunAt()).isEqualTo(ranAt);
        assertThat(found.lastRun()).isNotNull();
        assertThat(found.lastRun().path("datasets").path("orders").path("rows").get(0).path("id").asInt())
                .isEqualTo(1);
    }

    /**
     * The library sidebar loads every insight, so the blob must not ride along — this is the whole
     * reason opening an insight goes through findById rather than reusing the list entry.
     */
    @Test
    void findAllOmitsTheHeavyLastRunSnapshotButKeepsItsTimestamp() {
        String id = insert("Listed");
        Instant ranAt = Instant.parse("2026-05-02T10:00:00Z");
        insightRepository.updateLastRun(id, "{\"datasets\":{}}", ranAt);

        SavedInsight listed = insightRepository.findAll().stream()
                .filter(insight -> insight.id().equals(id)).findFirst().orElseThrow();

        assertThat(listed.lastRun()).isNull();
        assertThat(listed.lastRunAt()).isEqualTo(ranAt);
        assertThat(insightRepository.findById(id).orElseThrow().lastRun()).isNotNull();
    }

    /** Every row that predates the snapshot columns reads back null rather than throwing. */
    @Test
    void aRowWithNoSnapshotReadsBackAsNullInsteadOfFailing() {
        String id = insert("Never run");

        SavedInsight found = insightRepository.findById(id).orElseThrow();

        assertThat(found.lastRun()).isNull();
        assertThat(found.lastRunAt()).isNull();
    }

    /** A run is not an edit: it must not reorder the recency-sorted library. */
    @Test
    void updateLastRunLeavesTheDocumentAndUpdatedAtUntouched() {
        String id = "insight-" + UUID.randomUUID();
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        insightRepository.insert(new SavedInsight(id, "Name " + id, "desc", "source v1",
                "conn-1", created, created));

        insightRepository.updateLastRun(id, "{\"datasets\":{}}", Instant.parse("2026-06-01T00:00:00Z"));

        SavedInsight found = insightRepository.findById(id).orElseThrow();
        assertThat(found.source()).isEqualTo("source v1");
        assertThat(found.name()).isEqualTo("Name " + id);
        assertThat(found.updatedAt()).isEqualTo(created);
    }

    /** Saving a document edit must not discard the result the workspace is showing. */
    @Test
    void updatingTheDocumentDoesNotClearAStoredSnapshot() {
        String id = insert("Keeps snapshot");
        Instant ranAt = Instant.parse("2026-05-03T10:00:00Z");
        insightRepository.updateLastRun(id, "{\"datasets\":{}}", ranAt);
        SavedInsight existing = insightRepository.findById(id).orElseThrow();

        insightRepository.update(new SavedInsight(id, existing.name(), "edited", "source v2",
                existing.connectionId(), existing.createdAt(), Instant.now(),
                existing.lastRun(), existing.lastRunAt()));

        SavedInsight found = insightRepository.findById(id).orElseThrow();
        assertThat(found.source()).isEqualTo("source v2");
        assertThat(found.lastRun()).isNotNull();
        assertThat(found.lastRunAt()).isEqualTo(ranAt);
    }

    /** A corrupt snapshot must never stop an insight from opening. */
    @Test
    void corruptSnapshotJsonReadsBackAsNullInsteadOfThrowing() {
        String id = insert("Corrupt");

        insightRepository.updateLastRun(id, "{not json", Instant.parse("2026-05-04T10:00:00Z"));

        SavedInsight found = insightRepository.findById(id).orElseThrow();
        assertThat(found.lastRun()).isNull();
        assertThat(found.lastRunAt()).isNotNull();
    }

    private String insert(String label) {
        String id = "insight-" + UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        insightRepository.insert(new SavedInsight(id, label + " " + id, "", "source", null, now, now));
        return id;
    }

    @Test
    void updateOverwritesEveryMutableFieldButKeepsTheId() {
        String id = "insight-" + UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        insightRepository.insert(new SavedInsight(id, "Name v1", "Desc v1", "source v1",
                "conn-1", createdAt, createdAt));

        Instant updatedAt = Instant.parse("2026-02-01T00:00:00Z");
        insightRepository.update(new SavedInsight(id, "Name v2", "Desc v2", "source v2",
                null, createdAt, updatedAt));

        SavedInsight found = insightRepository.findById(id).orElseThrow();
        assertThat(found.name()).isEqualTo("Name v2");
        assertThat(found.description()).isEqualTo("Desc v2");
        assertThat(found.source()).isEqualTo("source v2");
        assertThat(found.connectionId()).isNull();
        assertThat(found.createdAt()).isEqualTo(createdAt);
        assertThat(found.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void deleteRemovesTheRow() {
        String id = "insight-" + UUID.randomUUID();
        Instant now = Instant.now();
        insightRepository.insert(new SavedInsight(id, "To delete", "", "source", null, now, now));
        assertThat(insightRepository.findById(id)).isPresent();

        insightRepository.delete(id);

        assertThat(insightRepository.findById(id)).isEmpty();
    }

    @Test
    void findAllOrdersByMostRecentlyUpdatedFirst() {
        String olderId = "insight-" + UUID.randomUUID();
        String newerId = "insight-" + UUID.randomUUID();
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-06-01T00:00:00Z");
        insightRepository.insert(new SavedInsight(olderId, "Older " + olderId, "", "source", null, older, older));
        insightRepository.insert(new SavedInsight(newerId, "Newer " + newerId, "", "source", null, newer, newer));

        var all = insightRepository.findAll();
        int olderIndex = indexOfId(all, olderId);
        int newerIndex = indexOfId(all, newerId);

        assertThat(newerIndex).isLessThan(olderIndex);
    }

    @Test
    void findByIdOnAMissingRowReturnsEmpty() {
        assertThat(insightRepository.findById("does-not-exist-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void findAllHonoursItsLimitAndStillReturnsTheMostRecentRows() {
        Instant base = Instant.parse("2026-03-01T00:00:00Z");
        String newestId = null;
        for (int i = 0; i < 3; i++) {
            String id = "insight-" + UUID.randomUUID();
            Instant updatedAt = base.plusSeconds(i);
            insightRepository.insert(new SavedInsight(id, "Limit probe " + id, "", "source", null, updatedAt, updatedAt));
            newestId = id;
        }

        var limited = insightRepository.findAll(2);

        assertThat(limited).hasSize(2);
        // Ordering is updated_at DESC, so a limit keeps the newest rows rather than truncating them.
        assertThat(limited.get(0).updatedAt()).isAfterOrEqualTo(limited.get(1).updatedAt());
        assertThat(insightRepository.findAll(1)).hasSize(1);
    }

    @Test
    void findAllClampsAnOutOfRangeLimitInsteadOfFailing() {
        assertThat(insightRepository.findAll(0)).hasSizeLessThanOrEqualTo(1);
        assertThat(insightRepository.findAll(-5)).hasSizeLessThanOrEqualTo(1);
    }

    private static int indexOfId(java.util.List<SavedInsight> insights, String id) {
        for (int i = 0; i < insights.size(); i++) {
            if (insights.get(i).id().equals(id)) return i;
        }
        throw new AssertionError("Insight " + id + " not found in findAll() result");
    }
}
