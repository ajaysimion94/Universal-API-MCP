package com.mcpserver.summaries;

import com.mcpserver.models.Chunk;
import com.mcpserver.repositories.ChunkRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SummaryExportServiceTests {

    private final ChunkRepository repository = mock(ChunkRepository.class);
    private final SummaryExportService service = new SummaryExportService(repository);

    @Test
    void exportsSelectedIndexedSourcesAsPlainText() {
        Chunk first = chunk("file-1", "Runbook.txt", "My files / Runbook.txt", 0, "Restart service A.");
        Chunk second = chunk("conn-1:JIRA-7", "JIRA-7 Outage", "OPS", 0, "Database outage.");
        when(repository.findForExport(Set.of("file-1"), Set.of("conn-1")))
                .thenReturn(List.of(first, second));

        SummaryExportService.ExportResult result =
                service.export(List.of("file-1"), List.of("conn-1"));

        assertThat(result.sourceCount()).isEqualTo(2);
        assertThat(result.chunkCount()).isEqualTo(2);
        assertThat(result.text())
                .contains("MCP KNOWLEDGE EXPORT")
                .contains("SOURCE: Runbook.txt")
                .contains("Restart service A.")
                .contains("SOURCE: JIRA-7 Outage")
                .contains("Database outage.");
        verify(repository).findForExport(Set.of("file-1"), Set.of("conn-1"));
    }

    @Test
    void rejectsAnEmptySelection() {
        assertThatThrownBy(() -> service.export(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Select at least one");
    }

    @Test
    void reportsWhenSelectedSourcesAreNotIndexed() {
        when(repository.findForExport(Set.of("file-1"), Set.of())).thenReturn(List.of());

        assertThatThrownBy(() -> service.export(List.of("file-1"), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no indexed content");
    }

    private static Chunk chunk(String sourceId, String name, String path, int position, String content) {
        return new Chunk(
                sourceId + "-" + position,
                sourceId,
                name,
                path,
                content,
                null,
                List.of("vis:everyone"),
                position,
                10,
                Instant.parse("2026-07-27T00:00:00Z"),
                sourceId.startsWith("conn-") ? "jira" : "upload",
                null,
                null,
                null
        );
    }
}
