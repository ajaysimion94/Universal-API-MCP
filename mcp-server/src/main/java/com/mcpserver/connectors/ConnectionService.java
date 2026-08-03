package com.mcpserver.connectors;

import com.mcpserver.cache.CacheService;
import com.mcpserver.repositories.ChunkRepository;
import com.mcpserver.tools.ApiToolService;
import com.mcpserver.tools.ToolGroupRepository;
import com.mcpserver.tools.JiraToolProvider;
import com.mcpserver.tools.ConfluenceToolProvider;
import com.mcpserver.tools.GitHubToolProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CRUD + lifecycle orchestration for {@link Connection}s. Mirrors {@code PluginRegistry}'s
 * async-job pattern (single-thread executor, job map, poll by id) for the two long-running
 * operations — testing credentials and backfilling — since both call out to a remote system and
 * must not block the REST request.
 */
@Service
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);

    private final ConnectionRepository connectionRepository;
    private final ChunkRepository chunkRepository;
    private final IngestionEventRepository eventRepository;
    private final CredentialCipher credentialCipher;
    private final WebhookTokenService webhookTokenService;
    private final ApiToolService apiToolService;
    private final ToolGroupRepository toolGroupRepository;
    private final JiraToolProvider jiraToolProvider;
    private final ConfluenceToolProvider confluenceToolProvider;
    private final GitHubToolProvider githubToolProvider;
    private final CacheService cacheService;
    private final Map<ConnectionType, SourceConnector> connectorsByType;

    private final ExecutorService jobExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "connection-job-worker");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ConnectionJob> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger jobCounter = new AtomicInteger(0);

    public ConnectionService(ConnectionRepository connectionRepository,
                              ChunkRepository chunkRepository,
                              IngestionEventRepository eventRepository,
                              CredentialCipher credentialCipher,
                              WebhookTokenService webhookTokenService,
                              ApiToolService apiToolService,
                              ToolGroupRepository toolGroupRepository,
                              JiraToolProvider jiraToolProvider,
                              ConfluenceToolProvider confluenceToolProvider,
                              GitHubToolProvider githubToolProvider,
                              CacheService cacheService,
                              List<SourceConnector> connectors) {
        this.connectionRepository = connectionRepository;
        this.chunkRepository = chunkRepository;
        this.eventRepository = eventRepository;
        this.credentialCipher = credentialCipher;
        this.webhookTokenService = webhookTokenService;
        this.apiToolService = apiToolService;
        this.toolGroupRepository = toolGroupRepository;
        this.jiraToolProvider = jiraToolProvider;
        this.confluenceToolProvider = confluenceToolProvider;
        this.githubToolProvider = githubToolProvider;
        this.cacheService = cacheService;
        this.connectorsByType = connectors.stream()
                .collect(Collectors.toMap(SourceConnector::type, Function.identity()));
    }

    public List<Connection> findAll() {
        return connectionRepository.findAll();
    }

    public Connection findById(String id) {
        return connectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + id));
    }

    public List<Connection> findConnected() {
        return connectionRepository.findByStatus(ConnectionStatus.CONNECTED);
    }

    /** Persists the connection (PENDING) and kicks off an async test-connection job. */
    public CreateResult create(ConnectionType type, String name, String baseUrl,
                          String username, String password, List<String> aclScope) {
        return create(type, name, baseUrl, AuthMode.BASIC, username, password, aclScope);
    }

    public CreateResult create(ConnectionType type, String name, String baseUrl, AuthMode authMode,
                               String username, String password, List<String> aclScope) {
        connectorFor(type); // fail fast if the type has no implementation yet
        validateAtlassianAuth(type, authMode, username, password);
        Connection connection = Connection.create(type, name, baseUrl, authMode,
                authMode == AuthMode.BEARER ? null : username,
                credentialCipher.encrypt(password), aclScope);
        connectionRepository.save(connection);
        return new CreateResult(connection.id(), startTestConnectionJob(connection.id(), true));
    }

    public record CreateResult(String connectionId, String jobId) {}

    /**
     * API_COLLECTION variant: carries the auth mode and the spec source (URL or raw uploaded
     * document — exactly one should be set). Base URL may be blank; the connector derives it from
     * the spec during the test job. The secret may be null for {@link AuthMode#NONE}.
     */
    public CreateResult createApiCollection(String name, String baseUrl, AuthMode authMode,
                                            String username, String secret, List<String> aclScope,
                                            String specUrl, String specDocument,
                                            ApiUrlMode apiUrlMode) {
        connectorFor(ConnectionType.API_COLLECTION);
        if ((specUrl == null || specUrl.isBlank()) && (specDocument == null || specDocument.isBlank())) {
            throw new IllegalArgumentException("Either a spec URL or an uploaded spec file is required");
        }
        Connection connection = Connection.create(ConnectionType.API_COLLECTION, name,
                baseUrl == null ? "" : baseUrl, authMode, username,
                secret == null || secret.isBlank() ? null : credentialCipher.encrypt(secret),
                aclScope).withApiUrlMode(apiUrlMode);
        connection = connection.withSpec(
                specUrl == null || specUrl.isBlank() ? null : specUrl.trim(), null, specDocument);
        connectionRepository.save(connection);
        return new CreateResult(connection.id(), startTestConnectionJob(connection.id()));
    }

    /** Re-tests credentials/reachability for an existing connection; returns the job id. */
    public String startTestConnectionJob(String connectionId) {
        return startTestConnectionJob(connectionId, false);
    }

    private String startTestConnectionJob(String connectionId, boolean initialSync) {
        String jobId = "job-" + jobCounter.incrementAndGet();
        ConnectionJob job = new ConnectionJob(
                connectionId, ConnectionJob.Kind.TEST_CONNECTION, initialSync);
        jobs.put(jobId, job);
        jobExecutor.submit(() -> runTestConnection(connectionId, job));
        return jobId;
    }

    /** Full historical crawl for an existing connection; returns the job id. */
    public String startBackfillJob(String connectionId) {
        String jobId = "job-" + jobCounter.incrementAndGet();
        ConnectionJob job = new ConnectionJob(
                connectionId, ConnectionJob.Kind.BACKFILL, false);
        jobs.put(jobId, job);
        jobExecutor.submit(() -> runBackfill(connectionId, job));
        return jobId;
    }

    public Optional<ConnectionJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /**
     * Updates name/base URL/ACL scope, and credentials if a new (non-blank) username or password
     * is supplied. {@code authMode} switches the connection's auth type (e.g. Bearer → Basic);
     * pass null to leave the current mode unchanged. Re-runs the test-connection job afterward
     * since the base URL or credentials may have changed; returns that job's id.
     */
    public String update(String connectionId, String name, String baseUrl,
                          String username, String password, AuthMode authMode, List<String> aclScope,
                          ApiUrlMode apiUrlMode) {
        Connection existing = findById(connectionId);
        AuthMode newAuthMode = authMode != null ? authMode : existing.authMode();
        String newUsername = username != null && !username.isBlank() ? username : existing.authUsername();
        String newBaseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : existing.baseUrl();
        boolean contentConnection = existing.type() == ConnectionType.JIRA
                || existing.type() == ConnectionType.CONFLUENCE;
        if (contentConnection && newAuthMode != AuthMode.BASIC && newAuthMode != AuthMode.BEARER) {
            throw new IllegalArgumentException("Atlassian connections support BASIC or BEARER authentication");
        }
        if (contentConnection && newAuthMode == AuthMode.BASIC
                && (newUsername == null || newUsername.isBlank())) {
            throw new IllegalArgumentException("Username is required for Atlassian Basic authentication");
        }
        if (contentConnection && authMode != null && authMode != existing.authMode()
                && (password == null || password.isBlank())) {
            throw new IllegalArgumentException("Enter a new password or token when changing authentication mode");
        }
        if (contentConnection && newAuthMode == AuthMode.BEARER) newUsername = null;
        boolean sourceChanged = contentConnection && !newBaseUrl.equals(existing.baseUrl());
        Connection updated = new Connection(
                existing.id(), existing.type(),
                name != null && !name.isBlank() ? name : existing.name(),
                newBaseUrl,
                sourceChanged ? DeploymentType.UNKNOWN : existing.deploymentType(), newAuthMode,
                newUsername,
                password != null && !password.isBlank() ? credentialCipher.encrypt(password) : existing.authSecretEncrypted(),
                ConnectionStatus.PENDING, null, sourceChanged ? null : existing.syncCursor(),
                sourceChanged ? false : existing.webhookRegistered(),
                aclScope != null ? aclScope : existing.aclScope(),
                existing.createdAt(), java.time.Instant.now(), existing.lastSyncedAt(),
                existing.specSourceUrl(), existing.specFormat(), existing.specDocument(),
                apiUrlMode != null ? apiUrlMode : existing.apiUrlMode()
        );
        connectionRepository.save(updated);
        return startTestConnectionJob(connectionId, sourceChanged);
    }

    public void setDisabled(String connectionId, boolean disabled) {
        Connection connection = findById(connectionId);
        if (disabled) {
            connectionRepository.save(connection.withStatus(ConnectionStatus.DISABLED, null));
        } else {
            connectionRepository.save(connection.withStatus(ConnectionStatus.PENDING, null));
            startTestConnectionJob(connectionId);
        }
    }

    /**
     * Deletes the connection, purges every chunk it produced (source_file_id prefix "{id}:"),
     * and removes its imported tools (no-op for connection types without tools) plus any tool
     * group memberships referencing it or its tools.
     */
    public void delete(String connectionId) {
        findById(connectionId); // 404s if missing
        chunkRepository.deleteBySourceFileIdPrefix(connectionId + ":");
        cacheService.invalidateSearchResults();
        apiToolService.deleteByConnectionId(connectionId); // also removes TOOL memberships
        toolGroupRepository.deleteMembersForConnection(connectionId);
        webhookTokenService.delete(connectionId);
        connectionRepository.deleteById(connectionId);
        log.info("Deleted connection {} and purged its chunks, tools, and group memberships", connectionId);
    }

    /**
     * Durably records an inbound webhook payload and returns immediately — actual dispatch to the
     * connector happens on {@link EventQueueWorker}'s background thread. This is what satisfies
     * the 3-second webhook-ack SLA and survives a crash between receipt and processing.
     */
    public void receiveWebhook(String connectionId, String callbackToken, String rawPayload) {
        Connection connection = findById(connectionId); // 404s if missing
        connectorFor(connection.type()); // fail fast if no connector implementation is registered
        if (!webhookTokenService.verify(connectionId, callbackToken)) {
            throw new SecurityException("Invalid webhook callback token");
        }
        eventRepository.insert(IngestionEvent.create(connectionId, EventType.WEBHOOK, null, rawPayload));
    }

    private static void validateAtlassianAuth(ConnectionType type, AuthMode authMode,
                                               String username, String password) {
        if (type != ConnectionType.JIRA && type != ConnectionType.CONFLUENCE) return;
        if (authMode != AuthMode.BASIC && authMode != AuthMode.BEARER) {
            throw new IllegalArgumentException("Atlassian connections support BASIC or BEARER authentication");
        }
        if (authMode == AuthMode.BASIC && (username == null || username.isBlank())) {
            throw new IllegalArgumentException("Username is required for Atlassian Basic authentication");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password or token is required for Atlassian authentication");
        }
    }

    private void runTestConnection(String connectionId, ConnectionJob job) {
        try {
            Connection connection = findById(connectionId);
            SourceConnector connector = connectorFor(connection.type());
            DeploymentType deployment = connector.detectDeployment(connection);
            connection = connection.withDeploymentType(deployment);
            connectionRepository.save(connection);
            connector.testConnection(connection);
            try {
                connector.registerWebhook(connection);
            } catch (Exception e) {
                log.info("Webhook registration skipped for {} ({}): falling back to polling only",
                        connectionId, e.getMessage());
            }

            // Register built-in tools for active connection
            Connection activeConnection = findById(connectionId);
            registerBuiltInToolsIfApplicable(activeConnection);

            // A newly-created content connection is not usable until its historical content has
            // been ingested. Keep it PENDING until that first backfill succeeds so "CONNECTED"
            // means the source has actually completed its initial sync. API_COLLECTION already
            // imports its tools in testConnection and has no content to crawl by default.
            if (job.initialSync && activeConnection.type() != ConnectionType.API_COLLECTION) {
                connector.backfill(activeConnection, (done, total) -> {
                    job.itemsProcessed = done;
                    job.itemsTotal = total;
                });
                connectionRepository.save(findById(connectionId)
                        .withLastSyncedAt(java.time.Instant.now()));
            }

            connectionRepository.save(findById(connectionId).withStatus(ConnectionStatus.CONNECTED, null));
            job.status = "completed";
            log.info("Connection {} verified ({})", connectionId, deployment);
        } catch (Exception e) {
            connectionRepository.save(findById(connectionId).withStatus(ConnectionStatus.ERROR, e.getMessage()));
            job.status = "failed";
            job.error = e.getMessage();
            log.warn("Connection {} test failed: {}", connectionId, e.getMessage());
        }
    }

    private void runBackfill(String connectionId, ConnectionJob job) {
        try {
            Connection connection = findById(connectionId);
            SourceConnector connector = connectorFor(connection.type());
            connector.backfill(connection, (done, total) -> {
                job.itemsProcessed = done;
                job.itemsTotal = total;
            });
            connectionRepository.save(findById(connectionId).withLastSyncedAt(java.time.Instant.now()));
            job.status = "completed";
            log.info("Backfill completed for connection {}", connectionId);
        } catch (Exception e) {
            job.status = "failed";
            job.error = e.getMessage();
            log.warn("Backfill failed for connection {}: {}", connectionId, e.getMessage());
        }
    }

    private void registerBuiltInToolsIfApplicable(Connection connection) {
        log.info("Registering built-in tools for connection {} of type {}", connection.id(), connection.type());
        if (connection.type() == ConnectionType.JIRA) {
            apiToolService.importTools(connection, jiraToolProvider.getDefinitions());
        } else if (connection.type() == ConnectionType.CONFLUENCE) {
            apiToolService.importTools(connection, confluenceToolProvider.getDefinitions());
        } else if (connection.type() == ConnectionType.GITHUB) {
            apiToolService.importTools(connection, githubToolProvider.getDefinitions());
        }
    }

    private SourceConnector connectorFor(ConnectionType type) {
        SourceConnector connector = connectorsByType.get(type);
        if (connector == null) {
            throw new IllegalArgumentException("No connector implementation registered for type: " + type);
        }
        return connector;
    }

    @PreDestroy
    void shutdown() {
        jobExecutor.shutdown();
        try {
            jobExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public static class ConnectionJob {
        public enum Kind { TEST_CONNECTION, BACKFILL }

        public final String connectionId;
        public final Kind kind;
        public volatile String status = "running";
        public volatile String error;
        public volatile int itemsProcessed = 0;
        public volatile int itemsTotal = 0;
        private final boolean initialSync;

        public ConnectionJob(String connectionId, Kind kind, boolean initialSync) {
            this.connectionId = connectionId;
            this.kind = kind;
            this.initialSync = initialSync;
        }
    }
}
