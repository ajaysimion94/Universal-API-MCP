package com.mcpserver.connectors;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A credentialed, schedulable connection to a remote knowledge source (Confluence, Jira; more
 * types reserved). Immutable — updates go through the {@code with*} methods and are persisted by
 * re-saving via {@link ConnectionRepository#save}.
 *
 * <p>The spec* fields are only populated for {@link ConnectionType#API_COLLECTION} connections:
 * the raw imported Postman collection / OpenAPI spec plus where it came from, kept so re-import
 * needs no file storage.
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
        Instant lastSyncedAt,
        String specSourceUrl,
        String specFormat,
        String specDocument,
        ApiUrlMode apiUrlMode
) {

    public static Connection create(ConnectionType type, String name, String baseUrl,
                                     String authUsername, String authSecretEncrypted,
                                     List<String> aclScope) {
        return create(type, name, baseUrl, AuthMode.BASIC, authUsername, authSecretEncrypted, aclScope);
    }

    public static Connection create(ConnectionType type, String name, String baseUrl,
                                     AuthMode authMode, String authUsername,
                                     String authSecretEncrypted, List<String> aclScope) {
        Instant now = Instant.now();
        return new Connection(
                UUID.randomUUID().toString(), type, name, baseUrl,
                DeploymentType.UNKNOWN, authMode, authUsername, authSecretEncrypted,
                ConnectionStatus.PENDING, null, null, false, aclScope,
                now, now, null, null, null, null, ApiUrlMode.CONNECTION_BASE
        );
    }

    public Connection withStatus(ConnectionStatus newStatus, String newLastError) {
        return new Connection(id, type, name, baseUrl, deploymentType, authMode, authUsername,
                authSecretEncrypted, newStatus, newLastError, syncCursor, webhookRegistered,
                aclScope, createdAt, Instant.now(), lastSyncedAt, specSourceUrl, specFormat, specDocument,
                apiUrlMode);
    }

    public Connection withDeploymentType(DeploymentType newDeploymentType) {
        return new Connection(id, type, name, baseUrl, newDeploymentType, authMode, authUsername,
                authSecretEncrypted, status, lastError, syncCursor, webhookRegistered,
                aclScope, createdAt, Instant.now(), lastSyncedAt, specSourceUrl, specFormat, specDocument,
                apiUrlMode);
    }

    public Connection withSyncCursor(String newSyncCursor) {
        return new Connection(id, type, name, baseUrl, deploymentType, authMode, authUsername,
                authSecretEncrypted, status, lastError, newSyncCursor, webhookRegistered,
                aclScope, createdAt, Instant.now(), Instant.now(), specSourceUrl, specFormat, specDocument,
                apiUrlMode);
    }

    public Connection withWebhookRegistered(boolean registered) {
        return new Connection(id, type, name, baseUrl, deploymentType, authMode, authUsername,
                authSecretEncrypted, status, lastError, syncCursor, registered,
                aclScope, createdAt, Instant.now(), lastSyncedAt, specSourceUrl, specFormat, specDocument,
                apiUrlMode);
    }

    public Connection withLastSyncedAt(Instant newLastSyncedAt) {
        return new Connection(id, type, name, baseUrl, deploymentType, authMode, authUsername,
                authSecretEncrypted, status, lastError, syncCursor, webhookRegistered,
                aclScope, createdAt, Instant.now(), newLastSyncedAt, specSourceUrl, specFormat, specDocument,
                apiUrlMode);
    }

    public Connection withSpec(String newSpecSourceUrl, String newSpecFormat, String newSpecDocument) {
        return new Connection(id, type, name, baseUrl, deploymentType, authMode, authUsername,
                authSecretEncrypted, status, lastError, syncCursor, webhookRegistered,
                aclScope, createdAt, Instant.now(), lastSyncedAt, newSpecSourceUrl, newSpecFormat, newSpecDocument,
                apiUrlMode);
    }

    public Connection withApiUrlMode(ApiUrlMode newApiUrlMode) {
        return new Connection(id, type, name, baseUrl, deploymentType, authMode, authUsername,
                authSecretEncrypted, status, lastError, syncCursor, webhookRegistered,
                aclScope, createdAt, Instant.now(), lastSyncedAt, specSourceUrl, specFormat, specDocument,
                newApiUrlMode == null ? ApiUrlMode.CONNECTION_BASE : newApiUrlMode);
    }
}
