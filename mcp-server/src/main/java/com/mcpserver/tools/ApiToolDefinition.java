package com.mcpserver.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Source-format-agnostic description of one importable request/operation — the common output of
 * {@link PostmanCollectionParser} and {@link OpenApiParser}, and the input to
 * {@code ApiToolService.importTools}.
 *
 * @param paramsSchema   JSON Schema (draft-07 subset: type/properties/required/enum/default)
 *                       describing every input as one flat object
 * @param paramLocations where each schema property goes at execution time:
 *                       {@code path | query | header | body}
 * @param staticHeaders  literal headers sent on every invocation (e.g. Content-Type)
 * @param bodyTemplate   example/skeleton JSON body from the spec, or null for body-less requests
 * @param primaryParam   the property free query text maps to (first required body string
 *                       property, else first required property), or null
 */
public record ApiToolDefinition(
        String displayName,
        String requestSlug,
        String description,
        String category,
        String httpMethod,
        String urlTemplate,
        ObjectNode paramsSchema,
        Map<String, String> paramLocations,
        Map<String, String> staticHeaders,
        String bodyTemplate,
        String primaryParam
) {
}
