package com.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;

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
}
