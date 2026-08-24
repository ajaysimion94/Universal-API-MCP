package com.mcpserver.connectors;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Connector telemetry deliberately avoids connection IDs, queries, URLs, and remote messages. */
@Component
public class ConnectorMetrics {

    private final MeterRegistry registry;

    public ConnectorMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(Connection connection, String operation, String outcome,
                       ConnectorFailureCategory failureCategory, Duration duration) {
        String deployment = connection.deploymentType() == null
                ? DeploymentType.UNKNOWN.name() : connection.deploymentType().name();
        String failure = failureCategory == null ? "NONE" : failureCategory.name();
        Timer.builder("connector.operation.duration")
                .tags("type", connection.type().name(), "deployment", deployment,
                        "operation", operation, "outcome", outcome, "failure", failure)
                .register(registry)
                .record(duration);
        registry.counter("connector.operation.total",
                "type", connection.type().name(), "deployment", deployment,
                "operation", operation, "outcome", outcome, "failure", failure).increment();
    }
}
