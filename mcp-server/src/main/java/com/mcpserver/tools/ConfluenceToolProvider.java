package com.mcpserver.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Provides first-party Confluence read + write tool definitions.
 */
@Component
public class ConfluenceToolProvider {
    private final ObjectMapper mapper = new ObjectMapper();

    public List<ApiToolDefinition> getDefinitions() {
        // confluence_search_pages
        ObjectNode searchSchema = mapper.createObjectNode();
        searchSchema.put("type", "object");
        ObjectNode searchProps = searchSchema.putObject("properties");
        searchProps.putObject("query").put("type", "string").put("description", "Confluence Query Language (CQL) query");
        searchSchema.putArray("required").add("query");

        ApiToolDefinition searchPages = new ApiToolDefinition(
                "Search Confluence Pages",
                "search_pages",
                "Search Confluence pages using CQL",
                "confluence",
                "GET",
                "/wiki/rest/api/content/search?cql={query}",
                searchSchema,
                Map.of("query", "query"),
                Map.of("Accept", "application/json"),
                null,
                "query"
        );

        // confluence_read_page
        ObjectNode readSchema = mapper.createObjectNode();
        readSchema.put("type", "object");
        ObjectNode readProps = readSchema.putObject("properties");
        readProps.putObject("pageId").put("type", "string").put("description", "Confluence page content ID");
        readSchema.putArray("required").add("pageId");

        ApiToolDefinition readPage = new ApiToolDefinition(
                "Read Confluence Page",
                "read_page",
                "Read the text content of a Confluence page by ID",
                "confluence",
                "GET",
                "/wiki/rest/api/content/{pageId}?expand=body.storage",
                readSchema,
                Map.of("pageId", "path"),
                Map.of("Accept", "application/json"),
                null,
                "pageId"
        );

        // confluence_create_page
        ObjectNode createSchema = mapper.createObjectNode();
        createSchema.put("type", "object");
        ObjectNode createProps = createSchema.putObject("properties");
        createProps.putObject("spaceKey").put("type", "string").put("description", "Space key");
        createProps.putObject("title").put("type", "string").put("description", "Page title");
        createProps.putObject("body").put("type", "string").put("description", "Page storage XHTML body content");
        createSchema.putArray("required").add("spaceKey").add("title").add("body");

        String createBodyTemplate = """
                {
                  "type": "page",
                  "title": "${title}",
                  "space": { "key": "${spaceKey}" },
                  "body": {
                    "storage": {
                      "value": "${body}",
                      "representation": "storage"
                    }
                  }
                }
                """;

        ApiToolDefinition createPage = new ApiToolDefinition(
                "Create Confluence Page",
                "create_page",
                "Create a new page in a Confluence space",
                "confluence",
                "POST",
                "/wiki/rest/api/content",
                createSchema,
                Map.of(
                        "spaceKey", "body",
                        "title", "body",
                        "body", "body"
                ),
                Map.of("Content-Type", "application/json", "Accept", "application/json"),
                createBodyTemplate,
                "title"
        );

        return List.of(searchPages, readPage, createPage);
    }
}
