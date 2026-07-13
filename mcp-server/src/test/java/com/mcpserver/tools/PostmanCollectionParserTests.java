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

    private static ApiToolDefinition byName(String displayName) {
        return defs.stream().filter(d -> d.displayName().equals(displayName)).findFirst().orElseThrow();
    }
}
