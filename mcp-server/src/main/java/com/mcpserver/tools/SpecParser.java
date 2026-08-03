package com.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpserver.connectors.AuthMode;

import java.util.List;

/**
 * Parses an API definition document (Postman collection / OpenAPI spec) into
 * {@link ApiToolDefinition}s. Implementations are hand-rolled Jackson tree-walkers — we extract
 * only method/URL/params/body-schema/tags, deliberately not a full-fidelity spec model
 * (see DECISIONS.md).
 */
public interface SpecParser {

    /** Cheap structural sniff — does this document look like this parser's format? */
    boolean supports(JsonNode root);

    /** Format name persisted on the connection: "POSTMAN" or "OPENAPI". */
    String format();

    List<ApiToolDefinition> parse(JsonNode root);

    /**
     * Parse with source URLs preserved when the caller explicitly selected that connection mode.
     * Parsers that can recover per-request/per-operation origins override this method; the default
     * retains the existing connection-relative behavior.
     */
    default List<ApiToolDefinition> parse(JsonNode root, boolean preserveSourceUrls) {
        return parse(root);
    }

    /**
     * Best-effort base URL suggestion when the connection didn't supply one explicitly.
     * Null when the format gives no usable signal — the caller then requires an explicit baseUrl.
     */
    default String extractBaseUrl(JsonNode root) {
        return null;
    }

    /**
     * Best-effort auth suggestion for pre-filling the connection setup form — never a secret
     * value, only the auth <em>shape</em>: which mode, and (for BASIC) a literal username or (for
     * API_KEY_HEADER) the header/query field name. The actual secret is always left for the user
     * to type. Null/blank {@code username} when nothing resolvable was found.
     */
    record DetectedAuth(AuthMode authMode, String username) {
        public static final DetectedAuth NONE = new DetectedAuth(AuthMode.NONE, null);
    }

    default DetectedAuth detectAuth(JsonNode root) {
        return DetectedAuth.NONE;
    }
}
