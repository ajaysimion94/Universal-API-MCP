package com.mcpserver.connectors;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionPollingSchedulerTests {

    @Test
    void slowConnectionDoesNotBlockOtherConnections() throws Exception {
        ConnectionRepository repository = mock(ConnectionRepository.class);
        SourceConnector jira = mock(SourceConnector.class);
        SourceConnector confluence = mock(SourceConnector.class);
        Connection jiraConnection = connected(ConnectionType.JIRA, "jira");
        Connection confluenceConnection = connected(ConnectionType.CONFLUENCE, "confluence");
        when(jira.type()).thenReturn(ConnectionType.JIRA);
        when(confluence.type()).thenReturn(ConnectionType.CONFLUENCE);
        when(repository.findByStatus(ConnectionStatus.CONNECTED))
                .thenReturn(List.of(jiraConnection, confluenceConnection));
        when(repository.findById(jiraConnection.id())).thenReturn(Optional.of(jiraConnection));
        when(repository.findById(confluenceConnection.id())).thenReturn(Optional.of(confluenceConnection));
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            bothStarted.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        }).when(jira).pollDelta(any());
        doAnswer(invocation -> {
            bothStarted.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        }).when(confluence).pollDelta(any());
        ConnectionPollingScheduler scheduler = new ConnectionPollingScheduler(
                repository, List.of(jira, confluence), 2);

        try {
            scheduler.pollAllConnections();
            assertThat(bothStarted.await(1, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            verify(jira, timeout(1_000)).pollDelta(jiraConnection);
            verify(confluence, timeout(1_000)).pollDelta(confluenceConnection);
        } finally {
            release.countDown();
            scheduler.shutdown();
        }
    }

    @Test
    void sameConnectionNeverOverlapsItself() throws Exception {
        ConnectionRepository repository = mock(ConnectionRepository.class);
        SourceConnector jira = mock(SourceConnector.class);
        Connection connection = connected(ConnectionType.JIRA, "jira-overlap");
        when(jira.type()).thenReturn(ConnectionType.JIRA);
        when(repository.findByStatus(ConnectionStatus.CONNECTED)).thenReturn(List.of(connection));
        when(repository.findById(connection.id())).thenReturn(Optional.of(connection));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        }).when(jira).pollDelta(any());
        ConnectionPollingScheduler scheduler = new ConnectionPollingScheduler(
                repository, List.of(jira), 2);

        try {
            scheduler.pollAllConnections();
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            scheduler.pollAllConnections();
            release.countDown();
            verify(jira, timeout(1_000).times(1)).pollDelta(connection);
        } finally {
            release.countDown();
            scheduler.shutdown();
        }
    }

    private static Connection connected(ConnectionType type, String name) {
        return Connection.create(type, name, "https://example.test", AuthMode.BASIC,
                "user", "encrypted", List.of()).withStatus(ConnectionStatus.CONNECTED, null);
    }
}
