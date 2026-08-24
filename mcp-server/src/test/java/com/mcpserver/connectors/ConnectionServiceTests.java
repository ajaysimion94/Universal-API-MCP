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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                mock(SourceCatalogRepository.class),
                credentialCipher,
                mock(WebhookTokenService.class),
                mock(ApiToolService.class),
                mock(ToolGroupRepository.class),
                mock(JiraToolProvider.class),
                mock(ConfluenceToolProvider.class),
                mock(GitHubToolProvider.class),
                mock(CacheService.class),
                mock(ConnectorMetrics.class),
                List.of(connector));

        service.create(ConnectionType.CONFLUENCE, "Docs", "https://docs.example.test",
                "user@example.test", "secret", List.of());

        verify(connector, timeout(2_000)).backfill(any(), any());
        verify(connectionRepository, timeout(2_000)).save(
                org.mockito.ArgumentMatchers.argThat(
                        connection -> connection.status() == ConnectionStatus.CONNECTED));
        assertThat(saved.get().lastSyncedAt()).isNotNull();
    }

    @Test
    void explicitHealthCheckVerifiesReadAccessWithoutStartingBackfill() throws Exception {
        ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
        SourceConnector connector = mock(SourceConnector.class);
        Connection connection = Connection.create(ConnectionType.JIRA, "Jira", "https://jira.example.test",
                "user", "encrypted", List.of()).withStatus(ConnectionStatus.CONNECTED, null);
        AtomicReference<Connection> saved = new AtomicReference<>(connection);
        when(connectionRepository.findById(connection.id())).thenAnswer(invocation -> Optional.of(saved.get()));
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(connectionRepository).save(any());
        when(connector.type()).thenReturn(ConnectionType.JIRA);
        when(connector.detectDeployment(any())).thenReturn(DeploymentType.CLOUD);
        ConnectionService service = service(connectionRepository, mock(IngestionEventRepository.class),
                mock(WebhookTokenService.class), connector);

        String jobId = service.startTestConnectionJob(connection.id());

        verify(connector, timeout(2_000)).verifyReadAccess(any());
        verify(connector, never()).backfill(any(), any());
        verify(connectionRepository, timeout(2_000)).save(
                org.mockito.ArgumentMatchers.argThat(value -> value.lastTestSucceededAt() != null));
        assertThat(service.getJob(jobId).orElseThrow().stage).isEqualTo("completed");
        assertThat(saved.get().lastTestedAt()).isNotNull();
        assertThat(saved.get().lastTestSucceededAt()).isNotNull();
        assertThat(saved.get().lastTestFailureCategory()).isNull();
        service.shutdown();
    }

    @Test
    void failedHealthCheckPersistsOnlyTheSafeFailureCategory() throws Exception {
        ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
        SourceConnector connector = mock(SourceConnector.class);
        Connection connection = Connection.create(ConnectionType.JIRA, "Jira", "https://jira.example.test",
                "user", "encrypted", List.of()).withStatus(ConnectionStatus.CONNECTED, null);
        AtomicReference<Connection> saved = new AtomicReference<>(connection);
        when(connectionRepository.findById(connection.id())).thenAnswer(invocation -> Optional.of(saved.get()));
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(connectionRepository).save(any());
        when(connector.type()).thenReturn(ConnectionType.JIRA);
        when(connector.detectDeployment(any())).thenThrow(new ConnectorException(
                ConnectorFailureCategory.AUTHENTICATION, "Jira deployment detection failed (HTTP 401)"));
        ConnectionService service = service(connectionRepository, mock(IngestionEventRepository.class),
                mock(WebhookTokenService.class), connector);

        String jobId = service.startTestConnectionJob(connection.id());

        verify(connectionRepository, timeout(2_000)).save(
                org.mockito.ArgumentMatchers.argThat(value -> value.status() == ConnectionStatus.ERROR));
        ConnectionService.ConnectionJob job = service.getJob(jobId).orElseThrow();
        assertThat(job.status).isEqualTo("failed");
        assertThat(job.failureCategory).isEqualTo("AUTHENTICATION");
        assertThat(job.error).doesNotContain("encrypted");
        assertThat(saved.get().lastTestFailureCategory()).isEqualTo("AUTHENTICATION");
        service.shutdown();
    }

    @Test
    void webhookIsRejectedBeforeQueueingWhenCallbackTokenIsInvalid() {
        ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
        IngestionEventRepository eventRepository = mock(IngestionEventRepository.class);
        WebhookTokenService webhookTokenService = mock(WebhookTokenService.class);
        SourceConnector connector = mock(SourceConnector.class);
        Connection connection = Connection.create(ConnectionType.JIRA, "Jira", "https://jira.test",
                "user", "encrypted", List.of());
        when(connectionRepository.findById(connection.id())).thenReturn(Optional.of(connection));
        when(connector.type()).thenReturn(ConnectionType.JIRA);
        when(webhookTokenService.verify(connection.id(), "wrong")).thenReturn(false);
        ConnectionService service = service(connectionRepository, eventRepository,
                webhookTokenService, connector);

        assertThatThrownBy(() -> service.receiveWebhook(connection.id(), "wrong", "{}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Invalid webhook callback token");
        verify(eventRepository, never()).insert(any());
        service.shutdown();
    }

    @Test
    void validWebhookTokenQueuesPayloadDurably() {
        ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
        IngestionEventRepository eventRepository = mock(IngestionEventRepository.class);
        WebhookTokenService webhookTokenService = mock(WebhookTokenService.class);
        SourceConnector connector = mock(SourceConnector.class);
        Connection connection = Connection.create(ConnectionType.CONFLUENCE, "Docs", "https://docs.test",
                "user", "encrypted", List.of());
        when(connectionRepository.findById(connection.id())).thenReturn(Optional.of(connection));
        when(connector.type()).thenReturn(ConnectionType.CONFLUENCE);
        when(webhookTokenService.verify(connection.id(), "valid")).thenReturn(true);
        ConnectionService service = service(connectionRepository, eventRepository,
                webhookTokenService, connector);

        service.receiveWebhook(connection.id(), "valid", "{\"page\":{\"id\":\"42\"}}");

        verify(eventRepository).insert(org.mockito.ArgumentMatchers.argThat(event ->
                event.connectionId().equals(connection.id())
                        && event.eventType() == EventType.WEBHOOK
                        && event.payload().contains("42")));
        service.shutdown();
    }

    @Test
    void apiCollectionUpdateSetsAndClearsBaseUrlOverride() throws Exception {
        ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
        SourceConnector connector = mock(SourceConnector.class);
        Connection connection = Connection.create(ConnectionType.API_COLLECTION, "Orders",
                        "https://source.example.test/api", AuthMode.NONE, null, null, List.of())
                .withSpec(null, "OPENAPI", "{}");
        AtomicReference<Connection> saved = new AtomicReference<>(connection);
        when(connectionRepository.findById(connection.id()))
                .thenAnswer(invocation -> Optional.of(saved.get()));
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(connectionRepository).save(any());
        when(connector.type()).thenReturn(ConnectionType.API_COLLECTION);
        when(connector.detectDeployment(any())).thenReturn(DeploymentType.UNKNOWN);
        ConnectionService service = service(connectionRepository, mock(IngestionEventRepository.class),
                mock(WebhookTokenService.class), connector);

        service.update(connection.id(), null, "https://override.example.test", null, null,
                null, null, null);

        assertThat(saved.get().baseUrl()).isEqualTo("https://source.example.test/api");
        assertThat(saved.get().baseUrlOverride()).isEqualTo("https://override.example.test");

        service.update(connection.id(), null, "", null, null, null, null, null);

        assertThat(saved.get().baseUrlOverride()).isNull();
        service.shutdown();
    }

    @Test
    void apiBaseUrlOverrideRequiresSafeAbsoluteHttpUrl() {
        assertThat(ConnectionService.normalizeApiBaseUrlOverride(" https://api.example.test/v1 "))
                .isEqualTo("https://api.example.test/v1");
        assertThatThrownBy(() -> ConnectionService.normalizeApiBaseUrlOverride("/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute HTTP(S)");
        assertThatThrownBy(() -> ConnectionService.normalizeApiBaseUrlOverride("https://user:pass@api.example.test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedded credentials");
    }

    private static ConnectionService service(ConnectionRepository connectionRepository,
                                             IngestionEventRepository eventRepository,
                                             WebhookTokenService webhookTokenService,
                                             SourceConnector connector) {
        return new ConnectionService(
                connectionRepository,
                mock(ChunkRepository.class),
                eventRepository,
                mock(SourceCatalogRepository.class),
                mock(CredentialCipher.class),
                webhookTokenService,
                mock(ApiToolService.class),
                mock(ToolGroupRepository.class),
                mock(JiraToolProvider.class),
                mock(ConfluenceToolProvider.class),
                mock(GitHubToolProvider.class),
                mock(CacheService.class),
                mock(ConnectorMetrics.class),
                List.of(connector));
    }
}
