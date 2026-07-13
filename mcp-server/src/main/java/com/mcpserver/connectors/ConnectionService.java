package com.mcpserver.connectors;

import com.mcpserver.repositories.ChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
                              List<SourceConnector> connectors) {
        this.connectionRepository = connectionRepository;
        this.chunkRepository = chunkRepository;
        this.eventRepository = eventRepository;
        this.credentialCipher = credentialCipher;
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
        connectorFor(type); // fail fast if the type has no implementation yet
        Connection connection = Connection.create(type, name, baseUrl, username,
                credentialCipher.encrypt(password), aclScope);
        connectionRepository.save(connection);
        return new CreateResult(connection.id(), startTestConnectionJob(connection.id()));
    }

    public record CreateResult(String connectionId, String jobId) {}

    /** Re-tests credentials/reachability for an existing connection; returns the job id. */
    public String startTestConnectionJob(String connectionId) {
        String jobId = "job-" + jobCounter.incrementAndGet();
        ConnectionJob job = new ConnectionJob(connectionId, ConnectionJob.Kind.TEST_CONNECTION);
        jobs.put(jobId, job);
        jobExecutor.submit(() -> runTestConnection(connectionId, job));
        return jobId;
    }

    /** Full historical crawl for an existing connection; returns the job id. */
    public String startBackfillJob(String connectionId) {
        String jobId = "job-" + jobCounter.incrementAndGet();
        ConnectionJob job = new ConnectionJob(connectionId, ConnectionJob.Kind.BACKFILL);
        jobs.put(jobId, job);
        jobExecutor.submit(() -> runBackfill(connectionId, job));
        return jobId;
    }

    public Optional<ConnectionJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /**
     * Updates name/base URL/ACL scope, and credentials if a new (non-blank) username or password
     * is supplied. Re-runs the test-connection job afterward since the base URL or credentials may
     * have changed; returns that job's id.
     */
    public String update(String connectionId, String name, String baseUrl,
                          String username, String password, List<String> aclScope) {
        Connection existing = findById(connectionId);
        Connection updated = new Connection(
                existing.id(), existing.type(),
                name != null && !name.isBlank() ? name : existing.name(),
                baseUrl != null && !baseUrl.isBlank() ? baseUrl : existing.baseUrl(),
                existing.deploymentType(), existing.authMode(),
                username != null && !username.isBlank() ? username : existing.authUsername(),
                password != null && !password.isBlank() ? credentialCipher.encrypt(password) : existing.authSecretEncrypted(),
                ConnectionStatus.PENDING, null, existing.syncCursor(), existing.webhookRegistered(),
                aclScope != null ? aclScope : existing.aclScope(),
                existing.createdAt(), java.time.Instant.now(), existing.lastSyncedAt()
        );
        connectionRepository.save(updated);
        return startTestConnectionJob(connectionId);
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

    /** Deletes the connection and purges every chunk it produced (source_file_id prefix "{id}:"). */
    public void delete(String connectionId) {
        findById(connectionId); // 404s if missing
        chunkRepository.deleteBySourceFileIdPrefix(connectionId + ":");
        connectionRepository.deleteById(connectionId);
        log.info("Deleted connection {} and purged its chunks", connectionId);
    }

    /**
     * Durably records an inbound webhook payload and returns immediately — actual dispatch to the
     * connector happens on {@link EventQueueWorker}'s background thread. This is what satisfies
     * the 3-second webhook-ack SLA and survives a crash between receipt and processing.
     */
    public void receiveWebhook(String connectionId, String rawPayload) {
        Connection connection = findById(connectionId); // 404s if missing
        connectorFor(connection.type()); // fail fast if no connector implementation is registered
        eventRepository.insert(IngestionEvent.create(connectionId, EventType.WEBHOOK, null, rawPayload));
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

    private SourceConnector connectorFor(ConnectionType type) {
        SourceConnector connector = connectorsByType.get(type);
        if (connector == null) {
            throw new IllegalArgumentException("No connector implementation registered for type: " + type);
        }
        return connector;
    }

    public static class ConnectionJob {
        public enum Kind { TEST_CONNECTION, BACKFILL }

        public final String connectionId;
        public final Kind kind;
        public volatile String status = "running";
        public volatile String error;
        public volatile int itemsProcessed = 0;
        public volatile int itemsTotal = 0;

        public ConnectionJob(String connectionId, Kind kind) {
            this.connectionId = connectionId;
            this.kind = kind;
        }
    }
}
