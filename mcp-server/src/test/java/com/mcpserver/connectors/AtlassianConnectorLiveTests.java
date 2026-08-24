package com.mcpserver.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcpserver.config.TlsHttpClientFactory;
import com.mcpserver.rag.retrieval.SearchPipeline.SearchResult;
import com.mcpserver.services.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;

/**
 * Credentialed acceptance tests. They are deliberately excluded from the ordinary Maven suite
 * and run only through {@code -Pconnector-live} on the private integration runner. Each test
 * creates a uniquely prefixed resource and removes it even when an assertion fails.
 */
@Tag("connector-live")
@SpringBootTest(properties = {
        "connectors.polling.enabled=false",
        "plugins.auto-start-enabled=false"
})
class AtlassianConnectorLiveTests {

    private static final String ACL_TAG = "connector-live";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofMinutes(2);

    @Autowired private ConnectionService connectionService;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private SourceCatalogRepository catalogRepository;
    @Autowired private SearchService searchService;
    @Autowired private List<SourceConnector> connectors;
    @Autowired private TlsHttpClientFactory tlsHttpClientFactory;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ThrowingRunnable> remoteCleanup = new ArrayList<>();
    private final List<String> localConnectionIds = new ArrayList<>();
    private HttpClient httpClient;

    @BeforeEach
    void requireLiveConfiguration() {
        required("ATLASSIAN_CLOUD_JIRA_BASE_URL");
        required("ATLASSIAN_CLOUD_CONFLUENCE_BASE_URL");
        required("ATLASSIAN_CLOUD_EMAIL");
        required("ATLASSIAN_CLOUD_API_TOKEN");
        required("ATLASSIAN_DC_JIRA_BASE_URL");
        required("ATLASSIAN_DC_CONFLUENCE_BASE_URL");
        required("ATLASSIAN_DC_JIRA_PAT");
        required("ATLASSIAN_DC_CONFLUENCE_PAT");
        httpClient = tlsHttpClientFactory.builder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterEach
    void cleanUp() {
        remoteCleanup.stream().sorted(Comparator.reverseOrder()).forEach(cleanup -> {
            try {
                cleanup.run();
            } catch (Exception ignored) {
                // Cleanup is deliberately best effort; the test failure remains the useful signal.
            }
        });
        for (String connectionId : localConnectionIds) {
            try {
                connectionService.delete(connectionId);
            } catch (Exception ignored) {
                // The in-memory test database is discarded after the test context exits.
            }
        }
    }

    @Test
    void cloudJiraSupportsCataloguingRetestDiscoveryPollingAndReconciliation() throws Exception {
        JiraFixture fixture = new JiraFixture(required("ATLASSIAN_CLOUD_JIRA_BASE_URL"),
                AtlassianAuth.basicAuthHeader(required("ATLASSIAN_CLOUD_EMAIL"),
                        required("ATLASSIAN_CLOUD_API_TOKEN")), true);
        exerciseJira(fixture, AuthMode.BASIC, required("ATLASSIAN_CLOUD_EMAIL"),
                required("ATLASSIAN_CLOUD_API_TOKEN"), DeploymentType.CLOUD);
    }

    @Test
    void dataCenterJiraSupportsCataloguingRetestDiscoveryPollingAndReconciliation() throws Exception {
        JiraFixture fixture = new JiraFixture(required("ATLASSIAN_DC_JIRA_BASE_URL"),
                AtlassianAuth.authorizationHeader(AuthMode.BEARER, null,
                        required("ATLASSIAN_DC_JIRA_PAT")), false);
        exerciseJira(fixture, AuthMode.BEARER, null, required("ATLASSIAN_DC_JIRA_PAT"),
                DeploymentType.SERVER_DC);
    }

    @Test
    void cloudConfluenceSupportsCataloguingRetestDiscoveryPollingAndReconciliation() throws Exception {
        ConfluenceFixture fixture = new ConfluenceFixture(required("ATLASSIAN_CLOUD_CONFLUENCE_BASE_URL"),
                AtlassianAuth.basicAuthHeader(required("ATLASSIAN_CLOUD_EMAIL"),
                        required("ATLASSIAN_CLOUD_API_TOKEN")), true);
        exerciseConfluence(fixture, AuthMode.BASIC, required("ATLASSIAN_CLOUD_EMAIL"),
                required("ATLASSIAN_CLOUD_API_TOKEN"), DeploymentType.CLOUD);
    }

    @Test
    void dataCenterConfluenceSupportsCataloguingRetestDiscoveryPollingAndReconciliation() throws Exception {
        ConfluenceFixture fixture = new ConfluenceFixture(required("ATLASSIAN_DC_CONFLUENCE_BASE_URL"),
                AtlassianAuth.authorizationHeader(AuthMode.BEARER, null,
                        required("ATLASSIAN_DC_CONFLUENCE_PAT")), false);
        exerciseConfluence(fixture, AuthMode.BEARER, null, required("ATLASSIAN_DC_CONFLUENCE_PAT"),
                DeploymentType.SERVER_DC);
    }

    private void exerciseJira(JiraFixture fixture, AuthMode authMode, String username, String secret,
                              DeploymentType expectedDeployment) throws Exception {
        Connection connection = connect(ConnectionType.JIRA, fixture.baseUrl, authMode, username, secret,
                expectedDeployment);
        String title = uniqueTitle("jira");
        String key = fixture.create(title);
        remoteCleanup.add(() -> fixture.delete(key));
        assertThat(catalogRepository.find(connection.id(), "jira", key)).isEmpty();

        assertMissDiscoveryProvidesCitedAclTaggedResult(title);
        fixture.update(key, title + " revised");
        sourceConnector(ConnectionType.JIRA).pollDelta(connectionService.findById(connection.id()));
        assertThat(catalogRepository.find(connection.id(), "jira", key)).isPresent();

        fixture.delete(key);
        awaitCompleted(connectionService.startBackfillJob(connection.id()));
        assertThat(catalogRepository.find(connection.id(), "jira", key)).isEmpty();
    }

    private void exerciseConfluence(ConfluenceFixture fixture, AuthMode authMode, String username, String secret,
                                    DeploymentType expectedDeployment) throws Exception {
        Connection connection = connect(ConnectionType.CONFLUENCE, fixture.baseUrl, authMode, username, secret,
                expectedDeployment);
        String title = uniqueTitle("confluence");
        String id = fixture.create(title);
        remoteCleanup.add(() -> fixture.delete(id));
        assertThat(catalogRepository.find(connection.id(), "confluence", id)).isEmpty();

        assertMissDiscoveryProvidesCitedAclTaggedResult(title);
        fixture.update(id, title + " revised");
        sourceConnector(ConnectionType.CONFLUENCE).pollDelta(connectionService.findById(connection.id()));
        assertThat(catalogRepository.find(connection.id(), "confluence", id)).isPresent();

        fixture.delete(id);
        awaitCompleted(connectionService.startBackfillJob(connection.id()));
        assertThat(catalogRepository.find(connection.id(), "confluence", id)).isEmpty();
    }

    private Connection connect(ConnectionType type, String baseUrl, AuthMode authMode, String username, String secret,
                               DeploymentType expectedDeployment) throws Exception {
        ConnectionService.CreateResult create = connectionService.create(type, "connector-live-" + type,
                baseUrl, authMode, username, secret, List.of(ACL_TAG));
        localConnectionIds.add(create.connectionId());
        awaitCompleted(create.jobId());
        Connection connected = connectionService.findById(create.connectionId());
        assertThat(connected.status()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(connected.deploymentType()).isEqualTo(expectedDeployment);
        assertThat(connected.lastTestSucceededAt()).isNotNull();
        assertThat(catalogRepository.findExternalIds(connected.id(),
                type == ConnectionType.JIRA ? "jira" : "confluence")).isNotNull();

        awaitCompleted(connectionService.startTestConnectionJob(connected.id()));
        Connection retested = connectionService.findById(connected.id());
        assertThat(retested.lastTestedAt()).isNotNull();
        assertThat(retested.lastTestSucceededAt()).isNotNull();
        assertThat(retested.lastTestFailureCategory()).isNull();
        return retested;
    }

    private void assertMissDiscoveryProvidesCitedAclTaggedResult(String title) throws Exception {
        long deadline = System.nanoTime() + DISCOVERY_TIMEOUT.toNanos();
        List<SearchResult> results = List.of();
        while (System.nanoTime() < deadline) {
            results = searchService.search(title, 5, List.of(ACL_TAG));
            for (SearchResult result : results) {
                if (title.equals(result.sourceName())) {
                    assertThat(result.sourceUrl()).isNotBlank();
                    assertThat(result.aclTags()).contains(ACL_TAG);
                    return;
                }
            }
            Thread.sleep(2_000);
        }
        fail("Miss-driven remote discovery did not return the created resource");
    }

    private SourceConnector sourceConnector(ConnectionType type) {
        return connectors.stream().filter(connector -> connector.type() == type).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing " + type + " connector"));
    }

    private void awaitCompleted(String jobId) throws Exception {
        long deadline = System.nanoTime() + JOB_TIMEOUT.toNanos();
        ConnectionService.ConnectionJob job;
        do {
            job = connectionService.getJob(jobId).orElseThrow();
            if (!"running".equals(job.status)) break;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        assertThat(job.status).as("connector job %s (%s)", jobId, job.failureCategory)
                .isEqualTo("completed");
    }

    private String uniqueTitle(String source) {
        return "connector-live-" + source + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("connector-live requires CI secret " + name);
        }
        return value.trim();
    }

    @FunctionalInterface
    private interface ThrowingRunnable extends Comparable<ThrowingRunnable> {
        void run() throws Exception;

        @Override
        default int compareTo(ThrowingRunnable other) {
            return 0;
        }
    }

    private abstract class Fixture {
        final String baseUrl;
        final String authorization;

        Fixture(String baseUrl, String authorization) {
            this.baseUrl = baseUrl.replaceAll("/+$", "");
            this.authorization = authorization;
        }

        JsonNode request(String method, String path, JsonNode body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", authorization)
                    .header("Accept", "application/json");
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),
                            StandardCharsets.UTF_8));
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            requireSuccess(method + " " + path.replaceAll("[?].*", ""), response.statusCode());
            return response.body().isBlank() ? mapper.nullNode() : mapper.readTree(response.body());
        }

        void deleteRequest(String path) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(REQUEST_TIMEOUT).header("Authorization", authorization)
                    .DELETE().build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 404) requireSuccess("DELETE resource", status);
        }

        void requireSuccess(String operation, int status) {
            if (status < 200 || status >= 300) {
                throw new AssertionError(operation + " returned HTTP " + status);
            }
        }

        abstract String create(String title) throws Exception;
        abstract void update(String id, String title) throws Exception;
        abstract void delete(String id) throws Exception;
    }

    private final class JiraFixture extends Fixture {
        private final boolean cloud;

        JiraFixture(String baseUrl, String authorization, boolean cloud) {
            super(baseUrl, authorization);
            this.cloud = cloud;
        }

        @Override
        String create(String title) throws Exception {
            String api = cloud ? "/rest/api/3" : "/rest/api/2";
            JsonNode projects = request("GET", api + (cloud ? "/project/search?maxResults=1" : "/project"), null);
            JsonNode project = cloud ? projects.path("values").path(0) : projects.path(0);
            String projectId = project.path("id").asText();
            if (projectId.isBlank()) throw new AssertionError("No Jira project is available for connector-live");
            JsonNode metadata = request("GET", api + "/issue/createmeta?projectIds=" + projectId
                    + "&expand=projects.issuetypes", null);
            JsonNode issueType = metadata.path("projects").path(0).path("issuetypes").path(0);
            String issueTypeId = issueType.path("id").asText();
            if (issueTypeId.isBlank()) throw new AssertionError("No creatable Jira issue type is available");
            ObjectNode fields = mapper.createObjectNode();
            fields.putObject("project").put("id", projectId);
            fields.putObject("issuetype").put("id", issueTypeId);
            fields.put("summary", title);
            ObjectNode payload = mapper.createObjectNode();
            payload.set("fields", fields);
            JsonNode created = request("POST", api + "/issue", payload);
            String key = created.path("key").asText();
            if (key.isBlank()) throw new AssertionError("Jira create response did not include an issue key");
            return key;
        }

        @Override
        void update(String key, String title) throws Exception {
            String api = cloud ? "/rest/api/3" : "/rest/api/2";
            ObjectNode payload = mapper.createObjectNode();
            payload.putObject("fields").put("summary", title);
            request("PUT", api + "/issue/" + key, payload);
        }

        @Override
        void delete(String key) throws Exception {
            deleteRequest((cloud ? "/rest/api/3" : "/rest/api/2") + "/issue/" + key);
        }
    }

    private final class ConfluenceFixture extends Fixture {
        private final boolean cloud;

        ConfluenceFixture(String baseUrl, String authorization, boolean cloud) {
            super(baseUrl, authorization);
            this.cloud = cloud;
        }

        @Override
        String create(String title) throws Exception {
            if (cloud) {
                String spaceId = request("GET", "/wiki/api/v2/spaces?limit=1", null)
                        .path("results").path(0).path("id").asText();
                if (spaceId.isBlank()) throw new AssertionError("No Confluence Cloud space is available");
                ObjectNode payload = mapper.createObjectNode();
                payload.put("spaceId", spaceId).put("status", "current").put("title", title);
                payload.putObject("body").put("representation", "storage")
                        .put("value", "<p>" + title + "</p>");
                String id = request("POST", "/wiki/api/v2/pages", payload).path("id").asText();
                if (id.isBlank()) throw new AssertionError("Confluence create response did not include a page id");
                return id;
            }
            String spaceKey = request("GET", "/rest/api/space?limit=1", null)
                    .path("results").path(0).path("key").asText();
            if (spaceKey.isBlank()) throw new AssertionError("No Confluence Data Center space is available");
            ObjectNode payload = mapper.createObjectNode();
            payload.put("type", "page").put("title", title);
            payload.putObject("space").put("key", spaceKey);
            payload.putObject("body").putObject("storage").put("representation", "storage")
                    .put("value", "<p>" + title + "</p>");
            String id = request("POST", "/rest/api/content", payload).path("id").asText();
            if (id.isBlank()) throw new AssertionError("Confluence create response did not include a page id");
            return id;
        }

        @Override
        void update(String id, String title) throws Exception {
            if (cloud) {
                JsonNode existing = request("GET", "/wiki/api/v2/pages/" + id + "?body-format=storage", null);
                ObjectNode payload = mapper.createObjectNode();
                payload.put("id", id).put("spaceId", existing.path("spaceId").asText())
                        .put("status", "current").put("title", title);
                payload.putObject("version").put("number", existing.path("version").path("number").asInt() + 1);
                payload.putObject("body").put("representation", "storage")
                        .put("value", "<p>" + title + " revised</p>");
                request("PUT", "/wiki/api/v2/pages/" + id, payload);
                return;
            }
            JsonNode existing = request("GET", "/rest/api/content/" + id + "?expand=version,space", null);
            ObjectNode payload = mapper.createObjectNode();
            payload.put("id", id).put("type", "page").put("title", title);
            payload.putObject("space").put("key", existing.path("space").path("key").asText());
            payload.putObject("version").put("number", existing.path("version").path("number").asInt() + 1);
            payload.putObject("body").putObject("storage").put("representation", "storage")
                    .put("value", "<p>" + title + " revised</p>");
            request("PUT", "/rest/api/content/" + id, payload);
        }

        @Override
        void delete(String id) throws Exception {
            deleteRequest(cloud ? "/wiki/api/v2/pages/" + id : "/rest/api/content/" + id);
        }
    }
}
