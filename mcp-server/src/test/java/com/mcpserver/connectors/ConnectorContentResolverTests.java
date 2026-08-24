package com.mcpserver.connectors;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorContentResolverTests {

    @Test
    void discoveryDeduplicatesAcrossSourcesAndNeverHydratesMoreThanTheGlobalBudget() throws Exception {
        ConnectionRepository connections = mock(ConnectionRepository.class);
        SourceCatalogRepository catalog = mock(SourceCatalogRepository.class);
        SourceConnector jira = mock(SourceConnector.class);
        SourceConnector confluence = mock(SourceConnector.class);
        when(jira.type()).thenReturn(ConnectionType.JIRA);
        when(confluence.type()).thenReturn(ConnectionType.CONFLUENCE);
        Connection jiraConnection = connected(ConnectionType.JIRA);
        Connection confluenceConnection = connected(ConnectionType.CONFLUENCE);
        when(connections.findByStatus(ConnectionStatus.CONNECTED)).thenReturn(List.of(jiraConnection, confluenceConnection));
        CatalogResource first = resource("first", jiraConnection.id());
        CatalogResource second = resource("second", confluenceConnection.id());
        CatalogResource third = resource("third", confluenceConnection.id());
        when(jira.discover(jiraConnection, "miss query", 3)).thenReturn(List.of(first, first));
        when(confluence.discover(confluenceConnection, "miss query", 2))
                .thenReturn(List.of(first, second, third));
        doNothing().when(jira).hydrate(eq(jiraConnection), any());
        doNothing().when(confluence).hydrate(eq(confluenceConnection), any());

        ConnectorContentResolver resolver = new ConnectorContentResolver(catalog, connections,
                List.of(jira, confluence), 3, 3, mock(ConnectorMetrics.class));

        assertThat(resolver.discoverAndHydrate("miss query")).isEqualTo(3);
        verify(jira).hydrate(jiraConnection, first);
        verify(confluence).hydrate(confluenceConnection, second);
        verify(confluence).hydrate(confluenceConnection, third);
        verify(catalog).markIndexed(first.id());
        verify(catalog).markIndexed(second.id());
        verify(catalog).markIndexed(third.id());
    }

    @Test
    void discoveryFailureIsBestEffortAndDoesNotEscapeToSearch() throws Exception {
        ConnectionRepository connections = mock(ConnectionRepository.class);
        SourceCatalogRepository catalog = mock(SourceCatalogRepository.class);
        SourceConnector jira = mock(SourceConnector.class);
        when(jira.type()).thenReturn(ConnectionType.JIRA);
        Connection connection = connected(ConnectionType.JIRA);
        when(connections.findByStatus(ConnectionStatus.CONNECTED)).thenReturn(List.of(connection));
        when(jira.discover(connection, "miss query", 3)).thenThrow(new ConnectorException(
                ConnectorFailureCategory.RATE_LIMIT, "Jira content discovery failed (HTTP 429)"));
        ConnectorContentResolver resolver = new ConnectorContentResolver(catalog, connections,
                List.of(jira), 3, 3, mock(ConnectorMetrics.class));

        assertThat(resolver.discoverAndHydrate("miss query")).isZero();
    }

    private static Connection connected(ConnectionType type) {
        return Connection.create(type, type.name(), "https://example.test", "user", "secret", List.of())
                .withStatus(ConnectionStatus.CONNECTED, null);
    }

    private static CatalogResource resource(String id, String connectionId) {
        return new CatalogResource(id, connectionId, "jira", id, null, null, id,
                "/resource/" + id, "https://example.test/" + id, Instant.now(),
                CatalogContentState.METADATA_ONLY, null, Instant.now());
    }
}
