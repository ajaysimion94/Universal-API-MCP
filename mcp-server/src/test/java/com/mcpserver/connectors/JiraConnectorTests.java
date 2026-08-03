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
class JiraConnectorTests {

    @Autowired
    private JiraConnector connector;
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
        Connection c = Connection.create(ConnectionType.JIRA, "Test-" + UUID.randomUUID(),
                "http://localhost:" + wireMock.port(), "user@example.com",
                credentialCipher.encrypt("token"), List.of());
        connectionRepository.save(c);
        return c;
    }

    @Test
    void detectsCloudDeploymentFromServerInfo() throws Exception {
        stubFor(get(urlEqualTo("/rest/api/2/serverInfo"))
                .willReturn(okJson("{\"deploymentType\":\"Cloud\"}")));

        assertThat(connector.detectDeployment(newConnection())).isEqualTo(DeploymentType.CLOUD);
    }

    @Test
    void detectsServerDcDeploymentFromServerInfo() throws Exception {
        stubFor(get(urlEqualTo("/rest/api/2/serverInfo"))
                .willReturn(okJson("{\"deploymentType\":\"Server\"}")));

        assertThat(connector.detectDeployment(newConnection())).isEqualTo(DeploymentType.SERVER_DC);
    }

    @Test
    void detectDeploymentThrowsOnUnreachableInstance() {
        stubFor(get(urlEqualTo("/rest/api/2/serverInfo")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> connector.detectDeployment(newConnection()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not reach Jira");
    }

    @Test
    void testConnectionThrowsWithStructuredMessageOnAuthFailure() {
        stubFor(get(urlEqualTo("/rest/api/2/myself"))
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));

        assertThatThrownBy(() -> connector.testConnection(newConnection()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    void backfillIngestsIssuesAsSearchableChunks() throws Exception {
        Connection c = newConnection();
        String key = "ENG-" + (int) (Math.random() * 100000);
        // Unique-per-run token: the dev SQLite DB persists across test runs (gitignored, not
        // cleaned between runs), so a shared phrase like "frobnicator crash" accumulates enough
        // matches over time that lexicalSearch's small topK can rank this run's chunk out.
        String uniqueTerm = "frobnicatorcrash" + key.replace("-", "");
        stubFor(get(urlPathEqualTo("/rest/api/2/search"))
                .willReturn(okJson(searchResultsJson(key, "Fix the " + uniqueTerm, "ENG",
                        "It crashes on startup.", "2024-01-01T12:00:00.000+0000"))));

        connector.backfill(c, (done, total) -> {});

        List<com.mcpserver.models.Chunk> hits = chunkRepository.lexicalSearch(uniqueTerm, 10);
        assertThat(hits).anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + key));
        assertThat(hits).anyMatch(h -> "jira".equals(h.sourceSystem()) && key.equals(h.externalId()));
    }

    @Test
    void backfillRendersCloudAdfDescriptionAndComments() throws Exception {
        Connection c = newConnection();
        String key = "ENG-" + (int) (Math.random() * 100000);
        String suffix = key.replace("-", "");
        String descriptionTerm = "adfdescription" + suffix;
        String commentTerm = "adfcomment" + suffix;
        stubFor(get(urlPathEqualTo("/rest/api/2/search"))
                .willReturn(okJson("""
                        {"total":1,"issues":[{"key":"%s","fields":{
                          "summary":"ADF issue","status":{"name":"Open"},
                          "assignee":{"displayName":"Ada"},"priority":{"name":"High"},
                          "labels":[],"project":{"key":"ENG"},
                          "description":{"type":"doc","version":1,"content":[
                            {"type":"paragraph","content":[{"type":"text","text":"%s"}]}]},
                          "updated":"2024-01-01T12:00:00.000+0000",
                          "comment":{"comments":[{"author":{"displayName":"Grace"},
                            "body":{"type":"doc","version":1,"content":[
                              {"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}}]}
                        }}]}
                        """.formatted(key, descriptionTerm, commentTerm))));

        connector.backfill(c, (done, total) -> {});

        assertThat(chunkRepository.lexicalSearch(descriptionTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + key));
        assertThat(chunkRepository.lexicalSearch(commentTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + key));
    }

    @Test
    void backfillFetchesDedicatedCommentPagesOnlyWhenInlineCommentsAreTruncated() throws Exception {
        Connection c = newConnection().withDeploymentType(DeploymentType.CLOUD);
        String key = "ENG-" + (int) (Math.random() * 100000);
        String uniqueTerm = "paginatedcomment" + key.replace("-", "");
        stubFor(get(urlPathEqualTo("/rest/api/2/search"))
                .willReturn(okJson("""
                        {"total":1,"issues":[{"key":"%s","fields":{
                          "summary":"Many comments","status":{"name":"Open"},
                          "assignee":{"displayName":"Ada"},"priority":{"name":"High"},
                          "labels":[],"project":{"key":"ENG"},"description":"Description",
                          "updated":"2024-01-01T12:00:00.000+0000",
                          "comment":{"startAt":0,"maxResults":1,"total":2,"comments":[
                            {"author":{"displayName":"One"},"body":"first"}]}
                        }}]}
                        """.formatted(key))));
        stubFor(get(urlPathEqualTo("/rest/api/3/issue/" + key + "/comment"))
                .withQueryParam("startAt", equalTo("0"))
                .willReturn(okJson("""
                        {"startAt":0,"maxResults":100,"total":2,"comments":[
                          {"author":{"displayName":"One"},"body":"first"},
                          {"author":{"displayName":"Two"},"body":"%s"}]}
                        """.formatted(uniqueTerm))));

        connector.backfill(c, (done, total) -> {});

        assertThat(chunkRepository.lexicalSearch(uniqueTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + key));
        verify(1, getRequestedFor(urlPathEqualTo("/rest/api/3/issue/" + key + "/comment")));
    }

    @Test
    void backfillUsesModernSearchEndpointWhenAvailable() throws Exception {
        // Simulates a current Jira Cloud tenant, where Atlassian retired GET /rest/api/2/search
        // in 2025 (it now returns 410 Gone there) and POST /rest/api/3/search/jql with
        // nextPageToken cursor pagination is the only way to search issues.
        Connection c = newConnection();
        String key1 = "ENG-" + (int) (Math.random() * 100000);
        String key2 = "ENG-" + (int) (Math.random() * 100000);
        String uniqueTerm = "modernendpoint" + key1.replace("-", "") + key2.replace("-", "");

        stubFor(post(urlEqualTo("/rest/api/3/search/jql"))
                .withRequestBody(matchingJsonPath("$.jql"))
                .withRequestBody(matchingJsonPath("$.fields"))
                .withRequestBody(equalToJson("{\"nextPageToken\":null}", true, true))
                .willReturn(okJson(modernSearchResultsJson(key1, uniqueTerm, "page-token-1"))));
        stubFor(post(urlEqualTo("/rest/api/3/search/jql"))
                .withRequestBody(matchingJsonPath("$.nextPageToken", equalTo("page-token-1")))
                .willReturn(okJson(modernSearchResultsJson(key2, uniqueTerm, null))));

        connector.backfill(c, (done, total) -> {});

        List<com.mcpserver.models.Chunk> hits = chunkRepository.lexicalSearch(uniqueTerm, 10);
        assertThat(hits).anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + key1));
        assertThat(hits).anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + key2));
        verify(0, getRequestedFor(urlPathEqualTo("/rest/api/2/search")));
    }

    @Test
    void fallsBackToLegacySearchWhenModernEndpointReturns410Gone() throws Exception {
        // The exact real-world scenario this fallback exists for: an instance where the modern
        // endpoint is unavailable/removed (410, matching what Atlassian's classic endpoints now
        // return on Cloud — here inverted to prove the *modern* path degrades gracefully too).
        Connection c = newConnection();
        String key = "ENG-" + (int) (Math.random() * 100000);
        String uniqueTerm = "goneendpoint" + key.replace("-", "");
        stubFor(post(urlEqualTo("/rest/api/3/search/jql")).willReturn(aResponse().withStatus(410)));
        stubFor(get(urlPathEqualTo("/rest/api/2/search"))
                .willReturn(okJson(searchResultsJson(key, uniqueTerm, "ENG",
                        "Legacy fallback content.", "2024-01-01T12:00:00.000+0000"))));

        connector.backfill(c, (done, total) -> {});

        assertThat(chunkRepository.lexicalSearch(uniqueTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + key));
    }

    @Test
    void fallsBackToLegacySearchOn400OnFirstProbeOnly() throws Exception {
        // Defends against the modern request shape itself being wrong (it was verified against
        // secondhand community reports, not Atlassian's own reference doc — a real possibility).
        // A malformed-request response (400) on the very first attempt for a connection is
        // treated the same as "endpoint not found" and falls back, exactly like 404/410.
        Connection c = newConnection();
        String key = "ENG-" + (int) (Math.random() * 100000);
        String uniqueTerm = "badrequestfallback" + key.replace("-", "");
        stubFor(post(urlEqualTo("/rest/api/3/search/jql")).willReturn(aResponse().withStatus(400)));
        stubFor(get(urlPathEqualTo("/rest/api/2/search"))
                .willReturn(okJson(searchResultsJson(key, uniqueTerm, "ENG",
                        "Legacy fallback after 400.", "2024-01-01T12:00:00.000+0000"))));

        connector.backfill(c, (done, total) -> {});

        assertThat(chunkRepository.lexicalSearch(uniqueTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + key));
    }

    @Test
    void doesNotFallBackOn400OnceModernIsConfirmedWorking() throws Exception {
        // Once the modern endpoint has been confirmed working for a connection, a later 400 is a
        // genuine request error (e.g. malformed JQL) — it must surface, not be silently swallowed
        // by switching to legacy (which would mask the real bug).
        Connection c = newConnection();
        stubFor(post(urlEqualTo("/rest/api/3/search/jql"))
                .withRequestBody(equalToJson("{\"nextPageToken\":null}", true, true))
                .willReturn(okJson(modernSearchResultsJson("ENG-1", "first call succeeds", null))));
        connector.backfill(c, (done, total) -> {}); // confirms modern support for this connection

        stubFor(post(urlEqualTo("/rest/api/3/search/jql")).willReturn(aResponse().withStatus(400).withBody("bad jql")));

        assertThatThrownBy(() -> connector.pollDelta(connectionRepository.findById(c.id())
                .orElseThrow().withSyncCursor("2020-01-01T00:00:00.000Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 400");
    }

    @Test
    void createThenEditSameIssueReplacesChunksInPlace() throws Exception {
        Connection c = newConnection();
        connectionRepository.save(c.withSyncCursor("2020-01-01T00:00:00.000Z"));
        String key = "ENG-" + (int) (Math.random() * 100000);
        String suffix = key.replace("-", "");
        String initialTerm = "initialreport" + suffix;
        String staleTerm = "stalesessiontoken" + suffix;

        stubFor(get(urlPathEqualTo("/rest/api/2/search"))
                .willReturn(okJson(searchResultsJson(key, "Login button broken", "ENG",
                        initialTerm + " from support.", "2024-01-01T00:00:00.000+0000"))));
        connector.pollDelta(connectionRepository.findById(c.id()).orElseThrow());

        assertThat(chunkRepository.lexicalSearch(initialTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + key));

        stubFor(get(urlPathEqualTo("/rest/api/2/search"))
                .willReturn(okJson(searchResultsJson(key, "Login button broken", "ENG",
                        "Root cause identified: " + staleTerm + ".", "2024-06-01T00:00:00.000+0000"))));
        connector.pollDelta(connectionRepository.findById(c.id()).orElseThrow());

        assertThat(chunkRepository.lexicalSearch(staleTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + key));
        assertThat(chunkRepository.lexicalSearch(initialTerm, 10))
                .noneMatch(h -> h.sourceFileId().equals(c.id() + ":" + key));
    }

    @Test
    void webhookDeletedEventPurgesChunks() throws Exception {
        Connection c = newConnection();
        String key = "ENG-" + (int) (Math.random() * 100000);
        String uniqueTerm = "temporaryticket" + key.replace("-", "");
        stubFor(get(urlPathEqualTo("/rest/api/2/search"))
                .willReturn(okJson(searchResultsJson(key, uniqueTerm, "ENG",
                        "Will be removed.", "2024-01-01T00:00:00.000+0000"))));
        connector.backfill(c, (done, total) -> {});
        assertThat(chunkRepository.lexicalSearch(uniqueTerm, 10))
                .anyMatch(h -> h.sourceFileId().equals(c.id() + ":" + key));

        connector.handleWebhookPayload(c, """
                {"webhookEvent":"jira:issue_deleted","issue":{"key":"%s"}}
                """.formatted(key));

        assertThat(chunkRepository.lexicalSearch(uniqueTerm, 10))
                .noneMatch(h -> h.sourceFileId().equals(c.id() + ":" + key));
    }

    private static String searchResultsJson(String key, String summary, String projectKey, String description, String updated) {
        return """
                {"total":1,"issues":[{"key":"%s","fields":{
                  "summary":"%s","status":{"name":"Open"},"assignee":{"displayName":"Ada"},
                  "priority":{"name":"High"},"labels":["bug"],"project":{"key":"%s"},
                  "description":"%s","updated":"%s","comment":{"comments":[]}
                }}]}
                """.formatted(key, summary, projectKey, description, updated);
    }

    /** Response shape for POST /rest/api/3/search/jql — no "total", cursor via nextPageToken. */
    private static String modernSearchResultsJson(String key, String summary, String nextPageToken) {
        String tokenField = nextPageToken == null ? "" : ",\"nextPageToken\":\"" + nextPageToken + "\"";
        return """
                {"issues":[{"key":"%s","fields":{
                  "summary":"%s","status":{"name":"Open"},"assignee":{"displayName":"Ada"},
                  "priority":{"name":"High"},"labels":["bug"],"project":{"key":"ENG"},
                  "description":"Modern endpoint content.","updated":"2024-01-01T12:00:00.000+0000",
                  "comment":{"comments":[]}
                }}]%s}
                """.formatted(key, summary, tokenField);
    }
}
