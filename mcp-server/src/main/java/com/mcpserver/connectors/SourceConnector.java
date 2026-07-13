package com.mcpserver.connectors;

/**
 * A connector to a remote knowledge source (Confluence, Jira, ...). Implementations write
 * fetched content through {@code IngestionService.ingest()}/{@code enqueue()} — the same
 * source-agnostic pipeline manual file upload uses — so connector-sourced content needs no
 * changes anywhere downstream of ingestion (chunking, embedding, search).
 */
public interface SourceConnector {

    ConnectionType type();

    /** Probes the base URL to determine Cloud vs. Server/Data Center. */
    DeploymentType detectDeployment(Connection connection) throws Exception;

    /** Validates the stored credentials against the source system; throws with a clear message on failure. */
    void testConnection(Connection connection) throws Exception;

    /** Full historical crawl. Reports progress via {@code sink} so the async job endpoint can be polled. */
    void backfill(Connection connection, BackfillProgressSink sink) throws Exception;

    /**
     * Attempts to register a push webhook with the source system so updates land as
     * {@link EventType#WEBHOOK} events instead of waiting for the next poll. Best-effort: no-ops
     * (and leaves {@code webhookRegistered=false}) when the source/deployment doesn't support it
     * — the connection still gets updates from {@link #pollDelta}.
     */
    void registerWebhook(Connection connection) throws Exception;

    /** Polls for changes since {@code connection.syncCursor()} and ingests/purges accordingly. */
    void pollDelta(Connection connection) throws Exception;

    /** Parses an inbound webhook payload and applies it (ingest or purge) directly. */
    void handleWebhookPayload(Connection connection, String rawPayload) throws Exception;
}
