package com.mcpserver.reports;

import com.mcpserver.cache.CacheService;
import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.ConnectionService;
import com.mcpserver.connectors.ConnectionStatus;
import com.mcpserver.connectors.ConnectionType;
import com.mcpserver.tools.ApiTool;
import com.mcpserver.tools.ApiToolExecutor;
import com.mcpserver.tools.ApiToolService;
import com.mcpserver.tools.ToolInvocationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportQueryServiceTests {

    private final ApiToolService tools = mock(ApiToolService.class);
    private final ConnectionService connections = mock(ConnectionService.class);
    private final ApiToolExecutor executor = mock(ApiToolExecutor.class);
    private final ReportQueryService service =
            new ReportQueryService(tools, connections, executor, new CacheService(60, 30));

    @Test
    void joinDoesNotMatchMissingKeysButStillMatchesAnExplicitEmptyString() throws Exception {
        Connection connection = connectedCollection();
        ApiTool left = tool(connection.id(), "left", "Left");
        ApiTool right = tool(connection.id(), "right", "Right");
        when(connections.findAll()).thenReturn(List.of(connection));
        when(tools.findByConnectionId(connection.id())).thenReturn(List.of(left, right));
        when(executor.execute(any(), any(), any())).thenAnswer(invocation -> {
            ApiTool requested = invocation.getArgument(0);
            String body = requested.id().equals("left")
                    ? """
                      [{"name":"missing-left"},{"id":"","name":"blank-left"},{"id":"1","name":"one"}]
                      """
                    : """
                      [{"department":"must-not-join"},{"id":"","department":"blank"},{"id":"1","department":"sales"}]
                      """;
            return new ToolInvocationResult(
                    200, 1, "application/json", body, false, "GET /", Map.of());
        });

        RqlModel.Execution execution = service.execute("""
                let left = request "Left";
                let right = request "Right";
                emit left |> join right on id = id prefix "right" as "joined";
                """, connection.id(), Map.of());

        List<Map<String, Object>> rows = execution.datasets().get("joined").rows();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).doesNotContainKeys("department", "right.department");
        assertThat(rows.get(1)).containsEntry("department", "blank");
        assertThat(rows.get(2)).containsEntry("department", "sales");
    }

    @Test
    void pendingCollectionsAreNotExposedToReportQueries() {
        Connection pending = connectedCollection().withStatus(ConnectionStatus.PENDING, null);
        when(connections.findAll()).thenReturn(List.of(pending));

        RqlModel.Analysis analysis = service.analyze(
                "let rows = request \"Left\";", pending.id(), null);

        assertThat(analysis.diagnostics()).extracting(RqlModel.Diagnostic::code).contains("RQL101");
        verifyNoInteractions(tools);
    }

    @Test
    void hugeLimitCannotOverflowTheSliceBoundary() throws Exception {
        Connection connection = connectedCollection();
        ApiTool left = tool(connection.id(), "left", "Left");
        when(connections.findAll()).thenReturn(List.of(connection));
        when(tools.findByConnectionId(connection.id())).thenReturn(List.of(left));
        when(executor.execute(any(), any(), any())).thenReturn(new ToolInvocationResult(
                200, 1, "application/json", "[{\"id\":1},{\"id\":2}]",
                false, "GET /", Map.of()));

        RqlModel.Execution execution = service.execute("""
                emit request "Left" |> offset 1 |> limit 999999999999 as "rows";
                """, connection.id(), Map.of());

        assertThat(execution.datasets().get("rows").rows())
                .singleElement().satisfies(row -> assertThat(row).containsEntry("id", 2L));
    }

    @Test
    void completionsReplacePartialKeywordsAndStagesAtTheCaret() {
        when(connections.findAll()).thenReturn(List.of());
        String statement = "le";

        RqlModel.Analysis statementAnalysis = service.analyze(statement, null, statement.length());

        assertThat(statementAnalysis.completions())
                .anySatisfy(completion -> {
                    assertThat(completion.label()).isEqualTo("let");
                    assertThat(completion.insertText()).isEqualTo("let ");
                    assertThat(completion.replaceSpan().startOffset()).isZero();
                    assertThat(completion.replaceSpan().endOffset()).isEqualTo(2);
                });

        String pipeline = "let rows = orders |> ord";
        RqlModel.Analysis pipelineAnalysis = service.analyze(pipeline, null, pipeline.length());

        assertThat(pipelineAnalysis.completions())
                .anySatisfy(completion -> {
                    assertThat(completion.label()).isEqualTo("order by");
                    assertThat(completion.replaceSpan().startOffset()).isEqualTo(pipeline.lastIndexOf("ord"));
                    assertThat(completion.replaceSpan().endOffset()).isEqualTo(pipeline.length());
                });
    }

    @Test
    void completionsIncludeDocumentAndQueryVariables() {
        when(connections.findAll()).thenReturn(List.of());
        String source = "set limit = 50; let rows = records |> where total > $";

        RqlModel.Analysis analysis = service.analyze(source, null, source.length(), Set.of("$minTotal"));

        assertThat(analysis.completions()).extracting(RqlModel.Completion::label)
                .contains("$limit", "$minTotal");
        assertThat(analysis.completions()).filteredOn(completion -> completion.label().equals("$limit"))
                .singleElement().satisfies(completion -> assertThat(completion.kind()).isEqualTo("VARIABLE"));
    }

    private static Connection connectedCollection() {
        return Connection.create(ConnectionType.API_COLLECTION, "Example", "https://example.test",
                null, null, List.of()).withStatus(ConnectionStatus.CONNECTED, null);
    }

    private static ApiTool tool(String connectionId, String id, String displayName) {
        Instant now = Instant.now();
        return new ApiTool(
                id, connectionId, "example", "example_" + id, id, displayName,
                "", "", "GET", "https://example.test/" + id,
                "{\"type\":\"object\"}", "{}", "{}", null, null,
                true, false, false, now, now);
    }
}
