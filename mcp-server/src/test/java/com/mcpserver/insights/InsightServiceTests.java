package com.mcpserver.insights;

import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.ConnectionService;
import com.mcpserver.connectors.ConnectionType;
import com.mcpserver.reports.ReportQueryService;
import com.mcpserver.reports.RqlModel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the CRUD/analyze/data paths that sit in front of {@link ReportQueryService} —
 * previously only {@link InsightDocumentParserTests} exercised this package, leaving connection
 * resolution, unknown-dataset validation (RQI101), and the one-bar-chart hint (RQI310) unverified.
 */
class InsightServiceTests {

    private final ReportQueryService reportQueryService = mock(ReportQueryService.class);
    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final InsightRepository insightRepository = mock(InsightRepository.class);
    private final InsightService service = new InsightService(reportQueryService, connectionService, insightRepository);

    // ── saved insight CRUD ──────────────────────────────────────────────────────

    @Test
    void createRejectsABlankName() {
        assertThatThrownBy(() -> service.create("  ", "desc", "source", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void createTrimsTheNameAndTreatsBlankConnectionIdAsUnset() {
        SavedInsight saved = service.create("  My insight  ", null, "source", "   ");

        assertThat(saved.name()).isEqualTo("My insight");
        assertThat(saved.description()).isEmpty();
        assertThat(saved.connectionId()).isNull();
        verify(insightRepository).insert(saved);
    }

    @Test
    void createRejectsAConnectionIdThatNamesNoConnection() {
        when(connectionService.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.create("My insight", null, "source", "ghost-conn"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost-conn");
        verify(insightRepository, never()).insert(any());
    }

    @Test
    void createAcceptsAConnectionIdThatExists() {
        Connection connection = Connection.create(ConnectionType.API_COLLECTION, "CRM App",
                "https://example.test", "user", "secret", List.of());
        when(connectionService.findAll()).thenReturn(List.of(connection));

        SavedInsight saved = service.create("My insight", null, "source", connection.id());

        assertThat(saved.connectionId()).isEqualTo(connection.id());
        verify(insightRepository).insert(saved);
    }

    @Test
    void updateRejectsAConnectionIdThatNamesNoConnection() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(insightRepository.findById("id-1")).thenReturn(Optional.of(
                new SavedInsight("id-1", "Name", "", "source", null, now, now)));
        when(connectionService.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.update("id-1", "Name", null, "source", "ghost-conn"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost-conn");
        verify(insightRepository, never()).update(any());
    }

    @Test
    void updatePreservesFieldsLeftNullAndKeepsTheOriginalCreatedAt() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        SavedInsight existing = new SavedInsight("id-1", "Old name", "Old description", "old source",
                "conn-1", createdAt, createdAt);
        when(insightRepository.findById("id-1")).thenReturn(Optional.of(existing));

        // A null connectionId clears the preferred app, so no connection lookup is involved.
        SavedInsight updated = service.update("id-1", "New name", null, "new source", null);

        assertThat(updated.name()).isEqualTo("New name");
        assertThat(updated.description()).isEqualTo("Old description");
        assertThat(updated.source()).isEqualTo("new source");
        assertThat(updated.connectionId()).isNull();
        assertThat(updated.createdAt()).isEqualTo(createdAt);
        verify(insightRepository).update(updated);
    }

    @Test
    void findByIdOnAMissingInsightFailsWithAnActionableMessage() {
        when(insightRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void deleteChecksExistenceBeforeDeleting() {
        when(insightRepository.findById("id-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("id-1")).isInstanceOf(IllegalArgumentException.class);
        verify(insightRepository, never()).delete(anyString());
    }

    // ── connection resolution ────────────────────────────────────────────────────

    @Test
    void analyzePrefersTheSuppliedConnectionOverTheDocumentsDeclaredOne() {
        stubAnalyze();
        String source = frontMatterDocument("declared-conn");

        service.analyze(source, "supplied-conn", null);

        verify(reportQueryService).analyze(anyString(), eq("supplied-conn"), any());
    }

    @Test
    void analyzeResolvesADeclaredConnectionByNameWhenNoIdIsSupplied() {
        Connection connection = Connection.create(ConnectionType.API_COLLECTION, "CRM App",
                "https://example.test", "user", "secret", List.of());
        when(connectionService.findAll()).thenReturn(List.of(connection));
        stubAnalyze();

        service.analyze(frontMatterDocument("CRM App"), null, null);

        verify(reportQueryService).analyze(anyString(), eq(connection.id()), any());
    }

    @Test
    void analyzeResolvesADeclaredConnectionBySlugWhenNoExactNameMatches() {
        Connection connection = Connection.create(ConnectionType.API_COLLECTION, "CRM App",
                "https://example.test", "user", "secret", List.of());
        when(connectionService.findAll()).thenReturn(List.of(connection));
        stubAnalyze();

        service.analyze(frontMatterDocument("crm-app"), null, null);

        verify(reportQueryService).analyze(anyString(), eq(connection.id()), any());
    }

    @Test
    void analyzeFallsBackToTheDeclaredValueWhenNoConnectionMatches() {
        when(connectionService.findAll()).thenReturn(List.of());
        stubAnalyze();

        service.analyze(frontMatterDocument("unknown-app"), null, null);

        verify(reportQueryService).analyze(anyString(), eq("unknown-app"), any());
    }

    // ── running a saved insight ──────────────────────────────────────────────────

    @Test
    void runStoresTheResultOnTheInsight() {
        stubInsight("id-1", rqlDocument("let a = request \"Rows\";"));
        stubExecution(2);

        InsightModel.RunResult result = service.run("id-1", rqlDocument("let a = request \"Rows\";"), null, Map.of());

        assertThat(result.stored()).isTrue();
        assertThat(result.ranAt()).isNotNull();
        assertThat(result.storeNote()).isNull();
        verify(insightRepository).updateLastRun(eq("id-1"), contains("\"datasets\""), any());
    }

    /**
     * Over the cap the snapshot is dropped whole rather than shortened: the browser computes
     * count/sum/avg over dataset rows, so a truncated snapshot would render wrong aggregates. The
     * returned data must still be complete — failing to store never degrades what is displayed.
     */
    @Test
    void aResultTooLargeToStoreIsStillReturnedInFull() {
        stubInsight("id-1", rqlDocument("let a = request \"Rows\";"));
        stubExecution(40_000);

        InsightModel.RunResult result = service.run("id-1", rqlDocument("let a = request \"Rows\";"), null, Map.of());

        assertThat(result.stored()).isFalse();
        assertThat(result.storeNote()).contains("too large");
        assertThat(result.ranAt()).isNull();
        assertThat(result.data().datasets().get("rows").rows()).hasSize(40_000);
        verify(insightRepository, never()).updateLastRun(any(), any(), any());
    }

    /** A reopened insight must never show numbers its stored document cannot account for. */
    @Test
    void aResultFromUnsavedEditsIsRunButNotStored() {
        stubInsight("id-1", rqlDocument("let a = request \"Rows\";"));
        stubExecution(1);

        InsightModel.RunResult result = service.run("id-1", rqlDocument("let a = request \"Rows\" |> limit 1;"), null, Map.of());

        assertThat(result.stored()).isFalse();
        assertThat(result.storeNote()).contains("Unsaved edits");
        assertThat(result.data()).isNotNull();
        verify(insightRepository, never()).updateLastRun(any(), any(), any());
    }

    @Test
    void runFallsBackToTheStoredSourceAndConnectionWhenTheBodyOmitsThem() {
        stubInsight("id-1", rqlDocument("let a = request \"Rows\";"), "conn-9");
        stubExecution(1);

        InsightModel.RunResult result = service.run("id-1", null, null, Map.of());

        assertThat(result.stored()).isTrue();
        verify(reportQueryService).execute(contains("let a = request"), eq("conn-9"), anyMap());
    }

    @Test
    void runOnAMissingInsightFailsWithAnActionableMessage() {
        when(insightRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.run("nope", "source", null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    /** Saving edits must not wipe the result the workspace is currently showing. */
    @Test
    void updateCarriesTheStoredSnapshotThrough() throws Exception {
        com.fasterxml.jackson.databind.JsonNode snapshot =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"datasets\":{}}");
        Instant ranAt = Instant.parse("2026-05-01T00:00:00Z");
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        when(insightRepository.findById("id-1")).thenReturn(Optional.of(new SavedInsight(
                "id-1", "Name", "", "source", null, createdAt, createdAt, snapshot, ranAt)));

        SavedInsight updated = service.update("id-1", "Name", null, "source v2", null);

        assertThat(updated.lastRun()).isSameAs(snapshot);
        assertThat(updated.lastRunAt()).isEqualTo(ranAt);
    }

    /** The draft path took no new required argument when /run was added alongside it. */
    @Test
    void dataStillRunsWithoutASavedInsight() {
        when(reportQueryService.execute(anyString(), any(), anyMap())).thenReturn(
                new RqlModel.Execution(Map.of(), List.of(), List.of()));

        assertThat(service.data("<Metrics />", null, Map.of())).isNotNull();
        verify(insightRepository, never()).updateLastRun(any(), any(), any());
    }

    // ── component/dataset validation ─────────────────────────────────────────────

    @Test
    void dataFlagsAComponentThatReferencesAnUndefinedDataset() {
        when(reportQueryService.execute(anyString(), any(), anyMap())).thenReturn(
                new RqlModel.Execution(Map.of(), List.of(), List.of()));

        InsightModel.Data result = service.data(
                "<DataTable data={missing} />", "conn-1", Map.of());

        assertThat(result.diagnostics()).extracting(RqlModel.Diagnostic::code).contains("RQI101");
    }

    @Test
    void dataHintsToUseAStatWhenABarChartHasExactlyOneRow() {
        RqlModel.Dataset dataset = new RqlModel.Dataset("by_user", List.of(Map.of("userId", 1, "posts", 3)));
        when(reportQueryService.execute(anyString(), any(), anyMap())).thenReturn(
                new RqlModel.Execution(Map.of("by_user", dataset), List.of(), List.of()));

        InsightModel.Data result = service.data(
                "<BarChart data={by_user} x=\"userId\" y=\"posts\" />", "conn-1", Map.of());

        assertThat(result.diagnostics()).extracting(RqlModel.Diagnostic::code).contains("RQI310");
        assertThat(result.datasets().get("by_user").rows()).hasSize(1);
    }

    @Test
    void dataDoesNotHintWhenABarChartHasSeveralRows() {
        RqlModel.Dataset dataset = new RqlModel.Dataset("by_user",
                List.of(Map.of("userId", 1, "posts", 3), Map.of("userId", 2, "posts", 1)));
        when(reportQueryService.execute(anyString(), any(), anyMap())).thenReturn(
                new RqlModel.Execution(Map.of("by_user", dataset), List.of(), List.of()));

        InsightModel.Data result = service.data(
                "<BarChart data={by_user} x=\"userId\" y=\"posts\" />", "conn-1", Map.of());

        assertThat(result.diagnostics()).extracting(RqlModel.Diagnostic::code).doesNotContain("RQI310");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** A real .rqd document — bare RQL is prose to the parser, leaving an empty program. */
    private static String rqlDocument(String pipeline) {
        return "```rql\n" + pipeline + "\n```\n";
    }

    private void stubInsight(String id, String source) {
        stubInsight(id, source, null);
    }

    private void stubInsight(String id, String source, String connectionId) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(insightRepository.findById(id)).thenReturn(Optional.of(
                new SavedInsight(id, "Name", "", source, connectionId, now, now)));
    }

    /** A dataset of {@code rows} synthetic rows, used to sit either side of the storage cap. */
    private void stubExecution(int rows) {
        List<Map<String, Object>> data = new java.util.ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(Map.of("id", i, "customer", "Customer number " + i, "total", i * 3));
        }
        when(reportQueryService.execute(anyString(), any(), anyMap())).thenReturn(
                new RqlModel.Execution(Map.of("rows", new RqlModel.Dataset("rows", data)),
                        List.of(), List.of()));
    }

    private void stubAnalyze() {
        when(reportQueryService.analyze(anyString(), any(), any()))
                .thenReturn(new RqlModel.Analysis(List.of(), List.of(), List.of()));
    }

    private static String frontMatterDocument(String connection) {
        return """
                ---
                connection: %s
                ---
                ```rql
                let rows = request "Ping";
                ```
                """.formatted(connection);
    }
}
