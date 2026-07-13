package com.mcpserver.connectors;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A credentialed, schedulable connection to a remote knowledge source (Confluence, Jira; more
 * types reserved). Immutable — updates go through the {@code with*} methods and are persisted by
 * re-saving via {@link ConnectionRepository#save}.
 */
public record Connection(
        String id,
        ConnectionType type,
        String name,
        String baseUrl,
        DeploymentType deploymentType,
        AuthMode authMode,
        String authUsername,
        String authSecretEncrypted,
        ConnectionStatus status,
        String lastError,
        String syncCursor,
        boolean webhookRegistered,
        List<String> aclScope,
        Instant createdAt,
        Instant updatedAt,
        Instant lastSyncedAt
) {

    public static Connection create(ConnectionType type, String name, String baseUrl,
                                     String authUsername, String authSecretEncrypted,
                                     List<String> aclScope) {
        Instant now = Instant.now();
        return new Connection(
                UUID.randomUUID().toString(), type, name, baseUrl,
                DeploymentType.UNKNOWN, AuthMode.BASIC, authUsername, authSecretEncrypted,
                ConnectionStatus.PENDING, null, null, false, aclScope,
                now, now, null
        );
    }

    public Connection withStatus(ConnectionStatus newStatus, String newLastError) {
        return new Connection(id, type, name, baseUrl, deploymentType, authMode, authUsername,
                authSecretEncrypted, newStatus, newLastError, syncCursor, webhookRegistered,
                aclScope, createdAt, Instant.now(), lastSyncedAt);
    }

    public Connection withDeploymentType(DeploymentType newDeploymentType) {
        return new Connection(id, type, name, baseUrl, newDeploymentType, authMode, authUsername,
                authSecretEncrypted, status, lastError, syncCursor, webhookRegistered,
                aclScope, createdAt, Instant.now(), lastSyncedAt);
    }

    public Connection withSyncCursor(String newSyncCursor) {
        return new Connection(id, type, name, baseUrl, deploymentType, authMode, authUsername,
                authSecretEncrypted, status, lastError, newSyncCursor, webhookRegistered,
                aclScope, createdAt, Instant.now(), Instant.now());
    }

    public Connection withWebhookRegistered(boolean registered) {
        return new Connection(id, type, name, baseUrl, deploymentType, authMode, authUsername,
                authSecretEncrypted, status, lastError, syncCursor, registered,
                aclScope, createdAt, Instant.now(), lastSyncedAt);
    }

    public Connection withLastSyncedAt(Instant newLastSyncedAt) {
        return new Connection(id, type, name, baseUrl, deploymentType, authMode, authUsername,
                authSecretEncrypted, status, lastError, syncCursor, webhookRegistered,
                aclScope, createdAt, Instant.now(), newLastSyncedAt);
    }
}
