package com.mcpserver.connectors;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.mcpserver.repositories.ChunkRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ConfluenceConnectorTests {

    @Autowired
    private ConfluenceConnector connector;
    @Autowired
    private ConnectionRepository connectionRepository;
    @Autowired
    private CredentialCipher credentialCipher;
    @Autowired
    private ChunkRepository chunkRepository;

    private WireMockServer wireMock;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        configureFor("localhost", wireMock.port());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private Connection newConnection() {
        Connection c = Connection.create(ConnectionType.CONFLUENCE, "Test-" + UUID.randomUUID(),
                "http://localhost:" + wireMock.port(), "user@example.com",
                credentialCipher.encrypt("token"), List.of());
        connectionRepository.save(c);
        return c;
    }

    @Test
    void detectsCloudDeploymentWhenWikiPathResponds() throws Exception {
        stubFor(get(urlPathEqualTo("/wiki/rest/api/space")).willReturn(okJson("{\"results\":[]}")));

        DeploymentType detected = connector.detectDeployment(newConnection());

        assertThat(detected).isEqualTo(DeploymentType.CLOUD);
    }

    @Test
    void detectsServerDcDeploymentWhenCloudPathFails() throws Exception {
        stubFor(get(urlPathEqualTo("/wiki/rest/api/space")).willReturn(aResponse().withStatus(404)));
        stubFor(get(urlPathEqualTo("/rest/api/space")).willReturn(okJson("{\"results\":[]}")));

        DeploymentType detected = connector.detectDeployment(newConnection());

        assertThat(detected).isEqualTo(DeploymentType.SERVER_DC);
    }

    @Test
    void detectDeploymentThrowsWhenNeitherPathResponds() {
        stubFor(get(urlPathEqualTo("/wiki/rest/api/space")).willReturn(aResponse().withStatus(404)));
        stubFor(get(urlPathEqualTo("/rest/api/space")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> connector.detectDeployment(newConnection()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not reach Confluence");
    }

    @Test
    void testConnectionThrowsWithStructuredMessageOnAuthFailure() {
        stubFor(get(urlPathEqualTo("/wiki/rest/api/user/current"))
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));
        Connection c = newConnection().withDeploymentType(DeploymentType.CLOUD);

        assertThatThrownBy(() -> connector.testConnection(c))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    void backfillIngestsPagesAsSearchableChunks() throws Exception {
        Connection c = newConnection().withDeploymentType(DeploymentType.CLOUD);
        String pageId = "pg-" + UUID.randomUUID();
        // Unique-per-run token: the dev SQLite DB persists across test runs (gitignored, not
        // cleaned between runs), so a shared phrase like "frobnicator" accumulates enough matches
        // over time that lexicalSearch's small topK can rank this run's chunk out of the results.
        String uniqueTerm = "frobnicator" + pageId.replace("-", "");
        stubFor(get(urlPathEqualTo("/wiki/rest/api/content"))
                .willReturn(okJson(pageListJson(pageId, "Runbook Alpha", "ENG",
                        "<p>Restart the " + uniqueTerm + " service when it hangs.</p>"))));

        connector.backfill(c, (done, total) -> {});

        List<com.mcpserver.models.Chunk> hits = chunkRepository.lexicalSearch(uniqueTerm, 10);
        assertThat(hits).anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + pageId));
        assertThat(hits).anyMatch(h -> "confluence".equals(h.sourceSystem()) && pageId.equals(h.externalId()));
    }

    @Test
    void pollDeltaReplacesChunksInPlaceOnEdit() throws Exception {
        Connection c = newConnection().withDeploymentType(DeploymentType.CLOUD);
        connectionRepository.save(c.withSyncCursor("2020-01-01T00:00:00.000Z"));
        String pageId = "pg-" + UUID.randomUUID();
        String suffix = pageId.replace("-", "");
        String originalTerm = "originalincident" + suffix;
        String updatedTerm = "updatedremediation" + suffix;

        stubFor(get(urlPathMatching("/wiki/rest/api/content/search.*"))
                .willReturn(okJson(searchResultsJson(pageId, "Runbook Beta", "ENG",
                        "<p>" + originalTerm + " notes.</p>", "2024-01-01T00:00:00.000Z"))));
        connector.pollDelta(connectionRepository.findById(c.id()).orElseThrow());

        assertThat(chunkRepository.lexicalSearch(originalTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + pageId));

        // Edit: same page id, new content, later timestamp — must replace, not duplicate.
        stubFor(get(urlPathMatching("/wiki/rest/api/content/search.*"))
                .willReturn(okJson(searchResultsJson(pageId, "Runbook Beta", "ENG",
                        "<p>" + updatedTerm + " steps.</p>", "2024-06-01T00:00:00.000Z"))));
        connector.pollDelta(connectionRepository.findById(c.id()).orElseThrow());

        assertThat(chunkRepository.lexicalSearch(updatedTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + pageId));
        assertThat(chunkRepository.lexicalSearch(originalTerm, 10))
                .noneMatch(h -> h.sourceFileId().equals(c.id() + ":" + pageId));

        Connection reloaded = connectionRepository.findById(c.id()).orElseThrow();
        assertThat(reloaded.syncCursor()).isEqualTo("2024-06-01T00:00:00.000Z");
    }

    @Test
    void pollDeltaFetchesEveryResultPageBeforeAdvancingCursor() throws Exception {
        Connection c = newConnection().withDeploymentType(DeploymentType.CLOUD);
        connectionRepository.save(c.withSyncCursor("2020-01-01T00:00:00.000Z"));
        String pageId = "pg-" + UUID.randomUUID();
        String uniqueTerm = "secondpageupdate" + pageId.replace("-", "");

        stubFor(get(urlPathEqualTo("/wiki/rest/api/content/search"))
                .withQueryParam("start", equalTo("0"))
                .willReturn(okJson(fullSearchPageJson())));
        stubFor(get(urlPathEqualTo("/wiki/rest/api/content/search"))
                .withQueryParam("start", equalTo("25"))
                .willReturn(okJson(searchResultsJson(pageId, "Second page", "ENG",
                        "<p>" + uniqueTerm + "</p>", "2024-06-02T00:00:00.000Z"))));

        connector.pollDelta(connectionRepository.findById(c.id()).orElseThrow());

        assertThat(chunkRepository.lexicalSearch(uniqueTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + pageId));
        assertThat(connectionRepository.findById(c.id()).orElseThrow().syncCursor())
                .isEqualTo("2024-06-02T00:00:00.000Z");
        verify(getRequestedFor(urlPathEqualTo("/wiki/rest/api/content/search"))
                .withQueryParam("start", equalTo("25")));
    }

    @Test
    void webhookPayloadPurgesChunksWhenPageNoLongerExists() throws Exception {
        Connection c = newConnection().withDeploymentType(DeploymentType.CLOUD);
        String pageId = "pg-" + UUID.randomUUID();
        String uniqueTerm = "tobedeleted" + pageId.replace("-", "");
        stubFor(get(urlPathEqualTo("/wiki/rest/api/content"))
                .willReturn(okJson(pageListJson(pageId, "Runbook Gamma", "ENG", "<p>" + uniqueTerm + ".</p>"))));
        connector.backfill(c, (done, total) -> {});
        assertThat(chunkRepository.lexicalSearch(uniqueTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + pageId));

        stubFor(get(urlPathEqualTo("/wiki/rest/api/content/" + pageId))
                .willReturn(aResponse().withStatus(404)));
        connector.handleWebhookPayload(c, "{\"page\":{\"id\":\"" + pageId + "\"}}");

        assertThat(chunkRepository.lexicalSearch(uniqueTerm, 10))
                .noneMatch(h -> h.sourceFileId().equals(c.id() + ":" + pageId));
    }

    private static String pageListJson(String id, String title, String spaceKey, String storageHtml) {
        return """
                {"results":[{"id":"%s","title":"%s","space":{"key":"%s"},
                "body":{"storage":{"value":"%s"}},
                "version":{"when":"2024-01-01T00:00:00.000Z"},
                "_links":{"webui":"/spaces/%s/pages/%s","base":"https://example.atlassian.net"}}]}
                """.formatted(id, title, spaceKey, storageHtml.replace("\"", "\\\""), spaceKey, id);
    }

    private static String searchResultsJson(String id, String title, String spaceKey, String storageHtml, String when) {
        return """
                {"results":[{"id":"%s","title":"%s","space":{"key":"%s"},
                "body":{"storage":{"value":"%s"}},
                "version":{"when":"%s"},
                "_links":{"webui":"/spaces/%s/pages/%s","base":"https://example.atlassian.net"}}]}
                """.formatted(id, title, spaceKey, storageHtml.replace("\"", "\\\""), when, spaceKey, id);
    }

    private static String fullSearchPageJson() {
        StringBuilder results = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            if (i > 0) results.append(',');
            results.append("""
                    {"id":"placeholder-%d","title":"Placeholder","space":{"key":"ENG"},
                    "body":{"storage":{"value":""}},
                    "version":{"when":"2024-06-01T00:00:00.000Z"}}
                    """.formatted(i));
        }
        return "{\"results\":[" + results + "],\"_links\":{\"next\":\"next-page\"}}";
    }
}
