package com.mcpserver.connectors;

import com.mcpserver.cache.CacheService;
import com.mcpserver.repositories.ChunkRepository;
import com.mcpserver.tools.ApiToolService;
import com.mcpserver.tools.ConfluenceToolProvider;
import com.mcpserver.tools.GitHubToolProvider;
import com.mcpserver.tools.JiraToolProvider;
import com.mcpserver.tools.ToolGroupRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionServiceTests {

    @Test
    void createCompletesInitialBackfillBeforeReportingConnected() throws Exception {
        ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
        CredentialCipher credentialCipher = mock(CredentialCipher.class);
        SourceConnector connector = mock(SourceConnector.class);
        AtomicReference<Connection> saved = new AtomicReference<>();

        when(connector.type()).thenReturn(ConnectionType.CONFLUENCE);
        when(connector.detectDeployment(any())).thenReturn(DeploymentType.CLOUD);
        when(credentialCipher.encrypt("secret")).thenReturn("encrypted");
        doAnswer(invocation -> {
            Connection connection = invocation.getArgument(0);
            saved.set(connection);
            return null;
        }).when(connectionRepository).save(any());
        when(connectionRepository.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(saved.get()));
        doAnswer(invocation -> {
            BackfillProgressSink sink = invocation.getArgument(1);
            sink.progress(1, 1);
            return null;
        }).when(connector).backfill(any(), any());

        ConnectionService service = new ConnectionService(
                connectionRepository,
                mock(ChunkRepository.class),
                mock(IngestionEventRepository.class),
                credentialCipher,
                mock(ApiToolService.class),
                mock(ToolGroupRepository.class),
                mock(JiraToolProvider.class),
                mock(ConfluenceToolProvider.class),
                mock(GitHubToolProvider.class),
                mock(CacheService.class),
                List.of(connector));

        service.create(ConnectionType.CONFLUENCE, "Docs", "https://docs.example.test",
                "user@example.test", "secret", List.of());

        verify(connector, timeout(2_000)).backfill(any(), any());
        verify(connectionRepository, timeout(2_000)).save(
                org.mockito.ArgumentMatchers.argThat(
                        connection -> connection.status() == ConnectionStatus.CONNECTED));
        assertThat(saved.get().lastSyncedAt()).isNotNull();
    }
}
