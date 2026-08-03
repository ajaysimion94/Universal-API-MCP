package com.mcpserver.connectors;

/**
 * Chooses how imported Postman/OpenAPI request URLs are resolved at execution time.
 * CONNECTION_BASE keeps every request pinned to the connection's configured base URL;
 * SOURCE_URLS preserves the absolute URL declared by each imported request/operation.
 */
public enum ApiUrlMode {
    CONNECTION_BASE,
    SOURCE_URLS
}
