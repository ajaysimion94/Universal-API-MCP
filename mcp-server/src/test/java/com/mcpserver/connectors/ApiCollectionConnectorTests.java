package com.mcpserver.connectors;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.mcpserver.repositories.ChunkRepository;
import com.mcpserver.tools.ApiTool;
import com.mcpserver.tools.ApiToolRepository;
import com.mcpserver.tools.ApiToolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ApiCollectionConnectorTests {

    @Autowired
    private ApiCollectionConnector connector;
    @Autowired
    private ConnectionRepository connectionRepository;
    @Autowired
    private ApiToolRepository apiToolRepository;
    @Autowired
    private ApiToolService apiToolService;
    @Autowired
    private ChunkRepository chunkRepository;

    private WireMockServer wireMock;
    private final List<String> createdConnectionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        configureFor("localhost", wireMock.port());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
        // tests share the real SQLite DB — remove everything this run created
        for (String id : createdConnectionIds) {
            apiToolRepository.deleteByConnectionId(id);
            chunkRepository.deleteBySourceFileIdPrefix(id + ":");
            connectionRepository.deleteById(id);
        }
    }

    private Connection newConnection(String specUrl, String specDocument) {
        Connection c = Connection.create(ConnectionType.API_COLLECTION,
                "Petstore-" + UUID.randomUUID(), "http://localhost:" + wireMock.port(),
                AuthMode.NONE, null, null, List.of());
        c = c.withSpec(specUrl, null, specDocument);
        connectionRepository.save(c);
        createdConnectionIds.add(c.id());
        return c;
    }

    private static String fixture(String path) {
        try (InputStream in = ApiCollectionConnectorTests.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void importsToolsFromDirectSpecUrlWithReadWriteSplit() throws Exception {
        stubFor(get(urlPathEqualTo("/v3/api-docs")).willReturn(okJson(fixture("/specs/petstore-openapi.json"))));
        Connection connection = newConnection("http://localhost:" + wireMock.port() + "/v3/api-docs", null);

        connector.testConnection(connection);

        List<ApiTool> tools = apiToolRepository.findByConnectionId(connection.id());
        assertThat(tools).hasSize(5);
        assertThat(tools).filteredOn(ApiTool::isRead)
                .allSatisfy(t -> {
                    assertThat(t.enabled()).isTrue();
                    assertThat(t.pending()).isFalse();
                });
        assertThat(tools).filteredOn(t -> !t.isRead())
                .allSatisfy(t -> {
                    assertThat(t.enabled()).isFalse();
                    assertThat(t.pending()).isTrue();
                });
        // spec document + format persisted for re-import
        Connection saved = connectionRepository.findById(connection.id()).orElseThrow();
        assertThat(saved.specFormat()).isEqualTo("OPENAPI");
        assertThat(saved.specDocument()).contains("\"openapi\"");
    }

    @Test
    void importsSwagger2FromDirectSwaggerJsonUrl() throws Exception {
        stubFor(get(urlPathEqualTo("/swagger.json"))
                .willReturn(okJson(fixture("/specs/petstore-swagger-2.json"))));
        Connection connection = newConnection(
                "http://localhost:" + wireMock.port() + "/swagger.json", null);

        connector.testConnection(connection);

        assertThat(apiToolRepository.findByConnectionId(connection.id()))
                .extracting(ApiTool::requestSlug)
                .containsExactlyInAnyOrder("list_pets", "create_pet");
        assertThat(connectionRepository.findById(connection.id()).orElseThrow().specFormat())
                .isEqualTo("OPENAPI");
    }

    @Test
    void resolvesSwaggerUiPageToUnderlyingSpec() throws Exception {
        String html = """
                <!DOCTYPE html><html><body>
                <script>
                  window.ui = SwaggerUIBundle({ url: '/v3/api-docs', dom_id: '#swagger-ui' });
                </script>
                </body></html>""";
        stubFor(get(urlPathEqualTo("/swagger-ui/index.html"))
                .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody(html)));
        stubFor(get(urlPathEqualTo("/v3/api-docs")).willReturn(okJson(fixture("/specs/petstore-openapi.json"))));

        Connection connection = newConnection(
                "http://localhost:" + wireMock.port() + "/swagger-ui/index.html", null);
        connector.testConnection(connection);

        assertThat(apiToolRepository.findByConnectionId(connection.id())).hasSize(5);
    }

    @Test
    void importsUploadedPostmanCollectionWithoutNetwork() throws Exception {
        Connection connection = newConnection(null, fixture("/specs/postman-todo.json"));

        connector.testConnection(connection);

        List<ApiTool> tools = apiToolRepository.findByConnectionId(connection.id());
        assertThat(tools).hasSize(4);
        assertThat(connectionRepository.findById(connection.id()).orElseThrow().specFormat())
                .isEqualTo("POSTMAN");
    }

    @Test
    void reimportPreservesAdminDecisionsAndDropsVanishedTools() throws Exception {
        Connection connection = newConnection(null, fixture("/specs/petstore-openapi.json"));
        connector.testConnection(connection);

        // admin enables a write tool
        ApiTool createPet = apiToolRepository.findByConnectionId(connection.id()).stream()
                .filter(t -> t.requestSlug().equals("create_pet")).findFirst().orElseThrow();
        apiToolService.setEnabled(createPet.id(), true);

        // spec shrinks to a single operation
        String shrunk = """
                {"openapi":"3.0.3","info":{"title":"Petstore","version":"1.0.0"},
                 "paths":{"/pets":{"post":{"operationId":"createPet","summary":"Create a pet"}}}}""";
        connection = connectionRepository.findById(connection.id()).orElseThrow()
                .withSpec(null, "OPENAPI", shrunk);
        connectionRepository.save(connection);
        connector.testConnection(connection);

        List<ApiTool> tools = apiToolRepository.findByConnectionId(connection.id());
        assertThat(tools).hasSize(1);
        ApiTool survivor = tools.get(0);
        assertThat(survivor.id()).isEqualTo(createPet.id());   // identity preserved
        assertThat(survivor.enabled()).isTrue();               // admin decision preserved
        assertThat(survivor.pending()).isFalse();
    }

    @Test
    void failsClearlyWhenNoSpecIsConfigured() {
        Connection connection = newConnection(null, null);

        assertThatThrownBy(() -> connector.testConnection(connection))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spec URL or an uploaded spec file");
    }

    @Test
    void knowledgeSourceRefreshIngestsToolResponseWithAclTags() throws Exception {
        String marker = "zzmarker" + UUID.randomUUID().toString().replace("-", "");
        stubFor(get(urlPathEqualTo("/pets"))
                .willReturn(okJson("[{\"name\":\"" + marker + "\",\"status\":\"available\"}]")));
        Connection connection = newConnection(null, fixture("/specs/petstore-openapi.json"));
        connector.testConnection(connection);

        ApiTool listPets = apiToolRepository.findByConnectionId(connection.id()).stream()
                .filter(t -> t.requestSlug().equals("list_pets")).findFirst().orElseThrow();
        apiToolService.setKnowledgeSource(listPets.id(), true);

        connector.pollDelta(connectionRepository.findById(connection.id()).orElseThrow());

        var chunks = chunkRepository.lexicalSearch(marker, 5);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).sourceFileId()).isEqualTo(connection.id() + ":" + listPets.id());
        assertThat(chunks.get(0).aclTags())
                .contains("connection:" + connection.id())
                .contains("api:" + listPets.appSlug());
    }
}
