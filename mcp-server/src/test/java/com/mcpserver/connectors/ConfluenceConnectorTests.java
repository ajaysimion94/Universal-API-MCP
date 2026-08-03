package com.mcpserver.connectors;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.mcpserver.repositories.ChunkRepository;
import com.mcpserver.services.IngestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
    @Autowired
    private IngestionService ingestionService;

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
        stubFor(get(urlPathEqualTo("/wiki/api/v2/spaces")).willReturn(okJson("{\"results\":[]}")));

        DeploymentType detected = connector.detectDeployment(newConnection());

        assertThat(detected).isEqualTo(DeploymentType.CLOUD);
    }

    @Test
    void detectsServerDcDeploymentWhenCloudPathFails() throws Exception {
        stubFor(get(urlPathEqualTo("/wiki/api/v2/spaces")).willReturn(aResponse().withStatus(404)));
        stubFor(get(urlPathEqualTo("/rest/api/space")).willReturn(okJson("{\"results\":[]}")));

        DeploymentType detected = connector.detectDeployment(newConnection());

        assertThat(detected).isEqualTo(DeploymentType.SERVER_DC);
    }

    @Test
    void detectDeploymentThrowsWhenNeitherPathResponds() {
        stubFor(get(urlPathEqualTo("/wiki/api/v2/spaces")).willReturn(aResponse().withStatus(404)));
        stubFor(get(urlPathEqualTo("/rest/api/space")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> connector.detectDeployment(newConnection()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not reach Confluence");
    }

    @Test
    void testConnectionThrowsWithStructuredMessageOnAuthFailure() {
        stubFor(get(urlPathEqualTo("/wiki/api/v2/users/current"))
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));
        Connection c = newConnection().withDeploymentType(DeploymentType.CLOUD);

        assertThatThrownBy(() -> connector.testConnection(c))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    void dataCenterPatUsesBearerAuthorization() throws Exception {
        Connection connection = Connection.create(ConnectionType.CONFLUENCE, "PAT", wireMock.baseUrl(),
                AuthMode.BEARER, null, credentialCipher.encrypt("dc-pat"), List.of())
                .withDeploymentType(DeploymentType.SERVER_DC);
        stubFor(get(urlEqualTo("/rest/api/user/current"))
                .withHeader("Authorization", equalTo("Bearer dc-pat"))
                .willReturn(okJson("{}")));

        connector.testConnection(connection);

        verify(getRequestedFor(urlEqualTo("/rest/api/user/current"))
                .withHeader("Authorization", equalTo("Bearer dc-pat")));
    }

    @Test
    void backfillIngestsPagesAsSearchableChunks() throws Exception {
        Connection c = newConnection().withDeploymentType(DeploymentType.CLOUD);
        String pageId = "pg-" + UUID.randomUUID();
        // Unique-per-run token: the dev SQLite DB persists across test runs (gitignored, not
        // cleaned between runs), so a shared phrase like "frobnicator" accumulates enough matches
        // over time that lexicalSearch's small topK can rank this run's chunk out of the results.
        String uniqueTerm = "frobnicator" + pageId.replace("-", "");
        stubFor(get(urlPathEqualTo("/wiki/api/v2/pages"))
                .willReturn(okJson(pageListJson(pageId, "Runbook Alpha", "ENG",
                        "<p>Restart the " + uniqueTerm + " service when it hangs.</p>"))));

        connector.backfill(c, (done, total) -> {});

        List<com.mcpserver.models.Chunk> hits = chunkRepository.lexicalSearch(uniqueTerm, 10);
        assertThat(hits).anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + pageId));
        assertThat(hits).anyMatch(h -> "confluence".equals(h.sourceSystem()) && pageId.equals(h.externalId()));
    }

    @Test
    void backfillCursorCoversChangesThatHappenDuringTheCrawl() throws Exception {
        Connection c = newConnection().withDeploymentType(DeploymentType.CLOUD);
        String pageId = "pg-" + UUID.randomUUID();
        stubFor(get(urlPathEqualTo("/wiki/api/v2/pages"))
                .willReturn(okJson(pageListJson(pageId, "Cursor coverage", "ENG", "<p>content</p>"))));
        AtomicReference<Instant> progressObservedAt = new AtomicReference<>();

        connector.backfill(c, (done, total) -> progressObservedAt.compareAndSet(null, Instant.now()));

        Instant cursor = Instant.parse(connectionRepository.findById(c.id()).orElseThrow().syncCursor());
        assertThat(cursor).isBeforeOrEqualTo(progressObservedAt.get());
    }

    @Test
    void pollDeltaReplacesChunksInPlaceOnEdit() throws Exception {
        Connection c = newConnection().withDeploymentType(DeploymentType.CLOUD);
        connectionRepository.save(c.withSyncCursor("2020-01-01T00:00:00.000Z"));
        String pageId = "pg-" + UUID.randomUUID();
        String suffix = pageId.replace("-", "");
        String originalTerm = "originalincident" + suffix;
        String updatedTerm = "updatedremediation" + suffix;

        stubFor(get(urlPathEqualTo("/wiki/api/v2/pages"))
                .withQueryParam("sort", equalTo("-modified-date"))
                .willReturn(okJson(searchResultsJson(pageId, "Runbook Beta", "ENG",
                        "<p>" + originalTerm + " notes.</p>", "2024-01-01T00:00:00.000Z"))));
        stubCloudInventory(pageId);
        connector.pollDelta(connectionRepository.findById(c.id()).orElseThrow());

        assertThat(chunkRepository.lexicalSearch(originalTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + pageId));

        // Edit: same page id, new content, later timestamp — must replace, not duplicate.
        stubFor(get(urlPathEqualTo("/wiki/api/v2/pages"))
                .withQueryParam("sort", equalTo("-modified-date"))
                .willReturn(okJson(searchResultsJson(pageId, "Runbook Beta", "ENG",
                        "<p>" + updatedTerm + " steps.</p>", "2024-06-01T00:00:00.000Z"))));
        connector.pollDelta(connectionRepository.findById(c.id()).orElseThrow());

        assertThat(chunkRepository.lexicalSearch(updatedTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + pageId));
        assertThat(chunkRepository.lexicalSearch(originalTerm, 10))
                .noneMatch(h -> h.sourceFileId().equals(c.id() + ":" + pageId));

        Connection reloaded = connectionRepository.findById(c.id()).orElseThrow();
        assertThat(Instant.parse(reloaded.syncCursor())).isEqualTo(Instant.parse("2024-06-01T00:00:00.000Z"));
    }

    @Test
    void pollDeltaFetchesEveryResultPageBeforeAdvancingCursor() throws Exception {
        Connection c = newConnection().withDeploymentType(DeploymentType.CLOUD);
        connectionRepository.save(c.withSyncCursor("2020-01-01T00:00:00.000Z"));
        String pageId = "pg-" + UUID.randomUUID();
        String uniqueTerm = "secondpageupdate" + pageId.replace("-", "");

        stubFor(get(urlPathEqualTo("/wiki/api/v2/pages"))
                .withQueryParam("sort", equalTo("-modified-date"))
                .withQueryParam("cursor", absent())
                .willReturn(okJson(fullSearchPageJson())));
        stubFor(get(urlPathEqualTo("/wiki/api/v2/pages"))
                .withQueryParam("cursor", equalTo("second-page"))
                .willReturn(okJson(searchResultsJson(pageId, "Second page", "ENG",
                        "<p>" + uniqueTerm + "</p>", "2024-06-02T00:00:00.000Z"))));
        stubCloudInventory(pageId);

        connector.pollDelta(connectionRepository.findById(c.id()).orElseThrow());

        assertThat(chunkRepository.lexicalSearch(uniqueTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + pageId));
        assertThat(Instant.parse(connectionRepository.findById(c.id()).orElseThrow().syncCursor()))
                .isEqualTo(Instant.parse("2024-06-02T00:00:00.000Z"));
        verify(getRequestedFor(urlPathEqualTo("/wiki/api/v2/pages"))
                .withQueryParam("cursor", equalTo("second-page")));
    }

    @Test
    void webhookPayloadPurgesChunksWhenPageNoLongerExists() throws Exception {
        Connection c = newConnection().withDeploymentType(DeploymentType.CLOUD);
        String pageId = "pg-" + UUID.randomUUID();
        String uniqueTerm = "tobedeleted" + pageId.replace("-", "");
        stubFor(get(urlPathEqualTo("/wiki/api/v2/pages"))
                .willReturn(okJson(pageListJson(pageId, "Runbook Gamma", "ENG", "<p>" + uniqueTerm + ".</p>"))));
        connector.backfill(c, (done, total) -> {});
        assertThat(chunkRepository.lexicalSearch(uniqueTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + pageId));

        stubFor(get(urlPathEqualTo("/wiki/api/v2/pages/" + pageId))
                .willReturn(aResponse().withStatus(404)));
        connector.handleWebhookPayload(c, "{\"page\":{\"id\":\"" + pageId + "\"}}");

        assertThat(chunkRepository.lexicalSearch(uniqueTerm, 10))
                .noneMatch(h -> h.sourceFileId().equals(c.id() + ":" + pageId));
    }

    @Test
    void reconciliationPurgesPagesNoLongerReturnedByConfluence() throws Exception {
        Connection c = newConnection().withDeploymentType(DeploymentType.CLOUD)
                .withSyncCursor("2020-01-01T00:00:00Z");
        connectionRepository.save(c);
        String pageId = "pg-" + UUID.randomUUID();
        String uniqueTerm = "deletedbyinventory" + pageId.replace("-", "");
        ingestionService.ingest(c.id() + ":" + pageId, "Deleted page", "ENG",
                uniqueTerm.getBytes(), "text/plain", List.of("connection:" + c.id()),
                "confluence", pageId, null, Instant.parse("2024-01-01T00:00:00Z"));
        stubFor(get(urlPathEqualTo("/wiki/api/v2/pages"))
                .withQueryParam("sort", equalTo("-modified-date"))
                .willReturn(okJson("{\"results\":[]}")));
        stubCloudInventory();

        connector.pollDelta(c);

        assertThat(chunkRepository.lexicalSearch(uniqueTerm, 10))
                .noneMatch(hit -> hit.sourceFileId().equals(c.id() + ":" + pageId));
    }

    private static String pageListJson(String id, String title, String spaceKey, String storageHtml) {
        return """
                {"results":[{"id":"%s","title":"%s","spaceId":"%s",
                "body":{"storage":{"value":"%s"}},
                "version":{"createdAt":"2024-01-01T00:00:00.000Z"},
                "_links":{"webui":"/spaces/%s/pages/%s"}}],
                "_links":{"base":"https://example.atlassian.net/wiki"}}
                """.formatted(id, title, spaceKey, storageHtml.replace("\"", "\\\""), spaceKey, id);
    }

    private static String searchResultsJson(String id, String title, String spaceKey, String storageHtml, String when) {
        return """
                {"results":[{"id":"%s","title":"%s","spaceId":"%s",
                "body":{"storage":{"value":"%s"}},
                "version":{"createdAt":"%s"},
                "_links":{"webui":"/spaces/%s/pages/%s"}}],
                "_links":{"base":"https://example.atlassian.net/wiki"}}
                """.formatted(id, title, spaceKey, storageHtml.replace("\"", "\\\""), when, spaceKey, id);
    }

    private static String fullSearchPageJson() {
        StringBuilder results = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            if (i > 0) results.append(',');
            results.append("""
                    {"id":"placeholder-%d","title":"Placeholder","spaceId":"ENG",
                    "body":{"storage":{"value":""}},
                    "version":{"createdAt":"2024-06-01T00:00:00.000Z"}}
                    """.formatted(i));
        }
        return "{\"results\":[" + results
                + "],\"_links\":{\"next\":\"/wiki/api/v2/pages?cursor=second-page\"}}";
    }

    private void stubCloudInventory(String... pageIds) {
        StringBuilder results = new StringBuilder();
        for (int i = 0; i < pageIds.length; i++) {
            if (i > 0) results.append(',');
            results.append("{\"id\":\"").append(pageIds[i]).append("\"}");
        }
        stubFor(get(urlPathEqualTo("/wiki/api/v2/pages"))
                .withQueryParam("limit", equalTo("250"))
                .willReturn(okJson("{\"results\":[" + results + "]}")));
    }
}
