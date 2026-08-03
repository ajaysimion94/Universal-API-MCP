package com.mcpserver.connectors;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Periodically polls every CONNECTED connection for changes (CQL/JQL delta queries) — the fallback
 * (and, for Cloud, the primary) sync path since webhook registration is best-effort and Server/DC
 * only. A connection set to DISABLED is excluded automatically by only querying CONNECTED status,
 * so disabling a connection pauses its polling with no extra bookkeeping.
 */
@Component
public class ConnectionPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPollingScheduler.class);

    private final ConnectionRepository connectionRepository;
    private final Map<ConnectionType, SourceConnector> connectorsByType;
    private final ExecutorService pollExecutor;
    private final Set<String> activeConnections = ConcurrentHashMap.newKeySet();

    public ConnectionPollingScheduler(ConnectionRepository connectionRepository,
                                      List<SourceConnector> connectors,
                                      @Value("${connectors.poll-concurrency:4}") int pollConcurrency) {
        this.connectionRepository = connectionRepository;
        this.connectorsByType = connectors.stream()
                .collect(Collectors.toMap(SourceConnector::type, Function.identity()));
        AtomicInteger threadNumber = new AtomicInteger();
        this.pollExecutor = Executors.newFixedThreadPool(Math.max(1, pollConcurrency), runnable -> {
            Thread thread = new Thread(runnable, "connection-poll-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${connectors.poll-interval-ms:300000}")
    public void pollAllConnections() {
        List<Connection> connected = connectionRepository.findByStatus(ConnectionStatus.CONNECTED);
        for (Connection connection : connected) {
            SourceConnector connector = connectorsByType.get(connection.type());
            if (connector == null) continue; // e.g. SHAREPOINT — reserved, no implementation yet
            if (!activeConnections.add(connection.id())) {
                log.debug("Skipping overlapping delta poll for connection {}", connection.id());
                continue;
            }
            pollExecutor.submit(() -> pollOne(connection, connector));
        }
    }

    private void pollOne(Connection connection, SourceConnector connector) {
        try {
            // Re-read after queueing so a connection disabled or edited while waiting does not run
            // with stale credentials/cursors.
            Connection current = connectionRepository.findById(connection.id()).orElse(null);
            if (current == null || current.status() != ConnectionStatus.CONNECTED) return;
            try {
                connector.pollDelta(current);
            } catch (Exception e) {
                log.warn("Delta poll failed for connection {} ({}): {}", current.id(), current.type(), e.getMessage());
            }
        } finally {
            activeConnections.remove(connection.id());
        }
    }

    @PreDestroy
    void shutdown() {
        pollExecutor.shutdown();
        try {
            if (!pollExecutor.awaitTermination(5, TimeUnit.SECONDS)) pollExecutor.shutdownNow();
        } catch (InterruptedException e) {
            pollExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
