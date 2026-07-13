package com.mcpserver.connectors;

/** Reported by a {@link SourceConnector} during {@link SourceConnector#backfill} so the async job can be polled. */
public interface BackfillProgressSink {
    void progress(int itemsProcessed, int itemsTotal);
}
