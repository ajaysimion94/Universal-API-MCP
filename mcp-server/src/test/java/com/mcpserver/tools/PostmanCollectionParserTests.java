package com.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests — no Spring context, no DB. */
class PostmanCollectionParserTests {

    private static final PostmanCollectionParser parser = new PostmanCollectionParser();
    private static JsonNode root;
    private static List<ApiToolDefinition> defs;

    @BeforeAll
    static void parseFixture() throws Exception {
        root = new ObjectMapper().readTree(
                PostmanCollectionParserTests.class.getResourceAsStream("/specs/postman-todo.json"));
        defs = parser.parse(root);
    }

    @Test
    void supportsDetectsPostmanCollections() {
        assertThat(parser.supports(root)).isTrue();
        assertThat(parser.format()).isEqualTo("POSTMAN");
    }

    @Test
    void parsesOneToolPerRequestAcrossFolders() {
        assertThat(defs).hasSize(4);
        assertThat(defs).extracting(ApiToolDefinition::requestSlug)
                .containsExactly("list_todos", "get_todo", "create_todo", "delete_todo");
    }

    @Test
    void folderBecomesCategoryAndRootFallsBackToGeneral() {
        assertThat(byName("List Todos").category()).isEqualTo("Todos");
        assertThat(byName("Delete Todo").category()).isEqualTo("general");
    }

    @Test
    void baseUrlVariableAndAbsoluteOriginAreStripped() {
        assertThat(byName("List Todos").urlTemplate()).isEqualTo("/todos");
        assertThat(byName("Delete Todo").urlTemplate()).isEqualTo("/todos/{todoId}");
    }

    @Test
    void pathVariablesBecomeRequiredStringParams() {
        ApiToolDefinition getTodo = byName("Get Todo");
        assertThat(getTodo.urlTemplate()).isEqualTo("/todos/{todoId}");
        assertThat(getTodo.paramLocations()).containsEntry("todoId", "path");
        assertThat(getTodo.paramsSchema().path("required").toString()).contains("todoId");
    }

    @Test
    void queryParamsAreOptionalWithDefaultsAndSkipDisabled() {
        ApiToolDefinition list = byName("List Todos");
        assertThat(list.paramLocations()).containsEntry("done", "query").containsEntry("limit", "query");
        assertThat(list.paramLocations()).doesNotContainKey("debug");
        assertThat(list.paramsSchema().path("properties").path("done").path("default").asText())
                .isEqualTo("false");
        assertThat(list.paramsSchema().path("properties").path("done").path("description").asText())
                .isEqualTo("Filter by completion");
    }

    @Test
    void exampleJsonBodyBecomesTypedRequiredParams() {
        ApiToolDefinition create = byName("Create Todo");
        assertThat(create.httpMethod()).isEqualTo("POST");
        JsonNode props = create.paramsSchema().path("properties");
        assertThat(props.path("title").path("type").asText()).isEqualTo("string");
        assertThat(props.path("priority").path("type").asText()).isEqualTo("integer");
        assertThat(props.path("done").path("type").asText()).isEqualTo("boolean");
        assertThat(create.paramLocations())
                .containsEntry("title", "body")
                .containsEntry("priority", "body")
                .containsEntry("done", "body");
        assertThat(create.bodyTemplate()).contains("Buy milk");
        assertThat(create.primaryParam()).isEqualTo("title");
    }

    @Test
    void literalHeadersAreStaticAndAuthorizationIsExcluded() {
        ApiToolDefinition create = byName("Create Todo");
        assertThat(create.staticHeaders())
                .containsEntry("X-Client", "postman")
                .containsEntry("Content-Type", "application/json")
                .doesNotContainKey("Authorization");
    }

    @Test
    void acceptsMetadataLightCollectionsAndResolvesUrlVariables() throws Exception {
        JsonNode collection = new ObjectMapper().readTree("""
                {
                  "info": {"name": "Variable URL"},
                  "variable": [
                    {"key": "protocol", "value": "https"},
                    {"key": "host", "value": "api.example.test"}
                  ],
                  "item": [{
                    "name": "List things",
                    "request": {
                      "method": "GET",
                      "url": {"raw": "{{protocol}}://{{host}}/v1/things"}
                    }
                  }]
                }
                """);

        assertThat(parser.supports(collection)).isTrue();
        assertThat(parser.extractBaseUrl(collection)).isEqualTo("https://api.example.test");
        assertThat(parser.parse(collection)).singleElement()
                .satisfies(def -> assertThat(def.urlTemplate()).isEqualTo("/v1/things"));
    }

    @Test
    void sourceUrlModePreservesEachRequestsResolvedAbsoluteHost() throws Exception {
        JsonNode collection = new ObjectMapper().readTree("""
                {
                  "info": {"_postman_id": "multi-host", "name": "Multi host"},
                  "variable": [{"key": "primary", "value": "https://api.example.test/v1"}],
                  "item": [
                    {"name": "Primary", "request": {"method": "GET", "url": "{{primary}}/items"}},
                    {"name": "Audit", "request": {"method": "GET", "url": "https://audit.example.test/events"}}
                  ]
                }
                """);

        assertThat(parser.parse(collection, true))
                .extracting(ApiToolDefinition::urlTemplate)
                .containsExactly("https://api.example.test/v1/items",
                        "https://audit.example.test/events");
        assertThat(parser.parse(collection))
                .extracting(ApiToolDefinition::urlTemplate)
                .containsExactly("/v1/items", "/events");
    }

    @Test
    void importsRawTextUrlEncodedAndGraphqlBodies() throws Exception {
        JsonNode collection = new ObjectMapper().readTree("""
                {
                  "info": {"_postman_id": "body-modes", "name": "Body modes"},
                  "item": [
                    {"name": "Raw XML", "request": {"method": "POST", "url": "{{baseUrl}}/xml",
                      "body": {"mode": "raw", "raw": "<item>{{itemName}}</item>",
                        "options": {"raw": {"language": "xml"}}}}},
                    {"name": "Login", "request": {"method": "POST", "url": "{{baseUrl}}/login",
                      "body": {"mode": "urlencoded", "urlencoded": [
                        {"key": "user-name", "value": "ajay"},
                        {"key": "disabled", "value": "no", "disabled": true}
                      ]}}},
                    {"name": "Graph", "request": {"method": "POST", "url": "{{baseUrl}}/graphql",
                      "body": {"mode": "graphql", "graphql": {
                        "query": "query Ping { ping }", "variables": "{\\"limit\\":2}"
                      }}}}
                  ],
                  "variable": [{"key": "baseUrl", "value": "https://api.example.test"}]
                }
                """);

        List<ApiToolDefinition> parsed = parser.parse(collection);
        ApiToolDefinition raw = parsed.get(0);
        assertThat(raw.bodyTemplate()).isEqualTo("<item>{item_name}</item>");
        assertThat(raw.staticHeaders()).containsEntry("Content-Type", "application/xml");
        assertThat(raw.paramLocations()).containsEntry("item_name", "body");

        ApiToolDefinition form = parsed.get(1);
        assertThat(form.bodyTemplate()).isEqualTo("user-name={user_name}");
        assertThat(form.staticHeaders()).containsEntry("Content-Type", "application/x-www-form-urlencoded");
        assertThat(form.paramsSchema().path("properties").path("user_name").path("default").asText())
                .isEqualTo("ajay");

        ApiToolDefinition graphql = parsed.get(2);
        assertThat(graphql.staticHeaders()).containsEntry("Content-Type", "application/json");
        assertThat(graphql.bodyTemplate()).contains("query Ping").contains("\"limit\":2");
    }

    private static ApiToolDefinition byName(String displayName) {
        return defs.stream().filter(d -> d.displayName().equals(displayName)).findFirst().orElseThrow();
    }
}
