package com.mcpserver.connectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
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

    public ConnectionPollingScheduler(ConnectionRepository connectionRepository, List<SourceConnector> connectors) {
        this.connectionRepository = connectionRepository;
        this.connectorsByType = connectors.stream()
                .collect(Collectors.toMap(SourceConnector::type, Function.identity()));
    }

    @Scheduled(fixedDelayString = "${connectors.poll-interval-ms:300000}")
    public void pollAllConnections() {
        List<Connection> connected = connectionRepository.findByStatus(ConnectionStatus.CONNECTED);
        for (Connection connection : connected) {
            SourceConnector connector = connectorsByType.get(connection.type());
            if (connector == null) continue; // e.g. SHAREPOINT — reserved, no implementation yet
            try {
                connector.pollDelta(connection);
            } catch (Exception e) {
                log.warn("Delta poll failed for connection {} ({}): {}", connection.id(), connection.type(), e.getMessage());
            }
        }
    }
}
