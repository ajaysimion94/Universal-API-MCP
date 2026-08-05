package com.mcpserver.connectors;

/**
 * A connector to a remote knowledge source (Confluence, Jira, ...). Implementations write
 * Remote metadata is catalogued first. Implementations fetch content through
 * {@code IngestionService.ingest()}/{@code enqueue()} only when a search strongly matches a
 * catalogued title, so a large tenant does not download and embed its complete history on connect.
 */
public interface SourceConnector {

    ConnectionType type();

    /** Probes the base URL to determine Cloud vs. Server/Data Center. */
    DeploymentType detectDeployment(Connection connection) throws Exception;

    /** Validates the stored credentials against the source system; throws with a clear message on failure. */
    void testConnection(Connection connection) throws Exception;

    /** Full metadata catalogue crawl. Page/issue bodies are not fetched by this operation. */
    void backfill(Connection connection, BackfillProgressSink sink) throws Exception;

    /**
     * Attempts to register a push webhook with the source system so updates land as
     * {@link EventType#WEBHOOK} events instead of waiting for the next poll. Best-effort: no-ops
     * (and leaves {@code webhookRegistered=false}) when the source/deployment doesn't support it
     * — the connection still gets updates from {@link #pollDelta}.
     */
    void registerWebhook(Connection connection) throws Exception;

    /** Polls metadata changes since {@code connection.syncCursor()} and marks changed content stale. */
    void pollDelta(Connection connection) throws Exception;

    /** Parses an inbound webhook payload and applies its metadata update or deletion. */
    void handleWebhookPayload(Connection connection, String rawPayload) throws Exception;

    /** Fetches one catalogued item's body and writes its searchable chunks. */
    default void hydrate(Connection connection, CatalogResource resource) throws Exception {
        throw new UnsupportedOperationException(type() + " does not support lazy content hydration");
    }
}
