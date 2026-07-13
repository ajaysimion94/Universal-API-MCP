package com.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests — no Spring context, no DB. */
class OpenApiParserTests {

    private static final OpenApiParser parser = new OpenApiParser();
    private static JsonNode root;
    private static List<ApiToolDefinition> defs;

    @BeforeAll
    static void parseFixture() throws Exception {
        root = new ObjectMapper().readTree(
                OpenApiParserTests.class.getResourceAsStream("/specs/petstore-openapi.json"));
        defs = parser.parse(root);
    }

    @Test
    void supportsDetectsOpenApiSpecs() {
        assertThat(parser.supports(root)).isTrue();
        assertThat(parser.format()).isEqualTo("OPENAPI");
    }

    @Test
    void parsesOneToolPerOperation() {
        assertThat(defs).hasSize(5);
        assertThat(defs).extracting(ApiToolDefinition::requestSlug)
                .containsExactlyInAnyOrder("list_pets", "create_pet", "get_pet_by_id",
                        "delete_pet", "list_orders");
    }

    @Test
    void tagBecomesCategoryWithPathSegmentFallback() {
        assertThat(byName("List pets").category()).isEqualTo("pets");
        assertThat(byName("List orders").category()).isEqualTo("store");
    }

    @Test
    void queryParamsCarryTypeDefaultAndEnum() {
        ApiToolDefinition list = byName("List pets");
        JsonNode props = list.paramsSchema().path("properties");
        assertThat(props.path("limit").path("type").asText()).isEqualTo("integer");
        assertThat(props.path("limit").path("default").asInt()).isEqualTo(20);
        assertThat(props.path("status").path("enum").toString()).contains("available");
        assertThat(list.paramLocations()).containsEntry("limit", "query");
    }

    @Test
    void pathLevelParametersApplyToEveryOperation() {
        ApiToolDefinition get = byName("Get pet by id");
        assertThat(get.urlTemplate()).isEqualTo("/pets/{petId}");
        assertThat(get.paramLocations()).containsEntry("petId", "path");
        assertThat(get.paramsSchema().path("required").toString()).contains("petId");
        assertThat(get.paramsSchema().path("properties").path("petId").path("type").asText())
                .isEqualTo("integer");
    }

    @Test
    void refRequestBodySchemaIsResolvedAndFlattened() {
        ApiToolDefinition create = byName("Create a pet");
        JsonNode props = create.paramsSchema().path("properties");
        assertThat(props.path("name").path("type").asText()).isEqualTo("string");
        assertThat(props.path("age").path("type").asText()).isEqualTo("integer");
        assertThat(create.paramLocations()).containsEntry("name", "body");
        assertThat(create.paramsSchema().path("required").toString()).contains("name");
        assertThat(create.primaryParam()).isEqualTo("name");
        assertThat(create.staticHeaders()).containsEntry("Content-Type", "application/json");
    }

    @Test
    void extractsServerUrlForBaseUrlPrefill() {
        assertThat(OpenApiParser.extractServerUrl(root))
                .isEqualTo("https://petstore.example.com/api/v1");
    }

    private static ApiToolDefinition byName(String displayName) {
        return defs.stream().filter(d -> d.displayName().equals(displayName)).findFirst().orElseThrow();
    }
}
