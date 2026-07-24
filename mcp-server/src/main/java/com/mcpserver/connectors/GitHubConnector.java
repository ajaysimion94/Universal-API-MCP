package com.mcpserver.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.services.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * GitHub connector (Cloud + Enterprise/Server).
 * Implements SourceConnector to test connection, perform backfill, delta polling, and hook webhook intake.
 */
@Component
public class GitHubConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(GitHubConnector.class);
    private final IngestionService ingestionService;
    private final ConnectionRepository connectionRepository;
    private final CredentialCipher credentialCipher;
    private final String webhookBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public GitHubConnector(IngestionService ingestionService,
                           ConnectionRepository connectionRepository,
                           CredentialCipher credentialCipher,
                           @Value("${connectors.webhook-base-url:}") String webhookBaseUrl) {
        this.ingestionService = ingestionService;
        this.connectionRepository = connectionRepository;
        this.credentialCipher = credentialCipher;
        this.webhookBaseUrl = webhookBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.GITHUB;
    }

    @Override
    public DeploymentType detectDeployment(Connection connection) throws Exception {
        String url = connection.baseUrl();
        if (url == null || url.isBlank() || url.contains("api.github.com") || url.contains("github.com")) {
            return DeploymentType.CLOUD;
        }
        return DeploymentType.SERVER_DC;
    }

    @Override
    public void testConnection(Connection connection) throws Exception {
        String apiEndpoint = getApiUrl(connection, "/user");
        HttpRequest request = buildRequest(connection, apiEndpoint, "GET", null);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub connection failed with status code " + response.statusCode()
                    + ": " + response.body());
        }
    }

    @Override
    public void backfill(Connection connection, BackfillProgressSink sink) throws Exception {
        log.info("Starting backfill for GitHub connection {}", connection.id());
        sink.progress(0, 100);

        // Fetch repositories
        String reposUrl = getApiUrl(connection, "/user/repos?per_page=10&sort=pushed");
        HttpRequest reposReq = buildRequest(connection, reposUrl, "GET", null);
        HttpResponse<String> reposResp = httpClient.send(reposReq, HttpResponse.BodyHandlers.ofString());

        if (reposResp.statusCode() != 200) {
            log.error("Failed to fetch GitHub repos: {}", reposResp.body());
            sink.progress(100, 100);
            return;
        }

        JsonNode repos = mapper.readTree(reposResp.body());
        if (!repos.isArray() || repos.isEmpty()) {
            log.info("No repositories found to backfill for connection {}", connection.id());
            sink.progress(100, 100);
            return;
        }

        int totalRepos = Math.min(repos.size(), 3); // Limit crawling to top 3 repos for backfill performance
        int count = 0;

        for (int i = 0; i < totalRepos; i++) {
            JsonNode repo = repos.get(i);
            String repoName = repo.path("full_name").asText();
            String defaultBranch = repo.path("default_branch").asText("main");

            log.info("Crawling default branch {} of GitHub repository {} for connection {}", defaultBranch, repoName, connection.id());

            // Get default branch tree (non-recursive for basic backfill)
            String treeUrl = getApiUrl(connection, "/repos/" + repoName + "/git/trees/" + defaultBranch);
            HttpRequest treeReq = buildRequest(connection, treeUrl, "GET", null);
            HttpResponse<String> treeResp = httpClient.send(treeReq, HttpResponse.BodyHandlers.ofString());

            if (treeResp.statusCode() == 200) {
                JsonNode tree = mapper.readTree(treeResp.body()).path("tree");
                if (tree.isArray()) {
                    for (JsonNode fileNode : tree) {
                        String type = fileNode.path("type").asText();
                        String path = fileNode.path("path").asText();
                        if ("blob".equals(type) && (path.endsWith(".md") || path.endsWith(".txt"))) {
                            String fileUrl = fileNode.path("url").asText();
                            HttpRequest fileReq = buildRequest(connection, fileUrl, "GET", null);
                            HttpResponse<String> fileResp = httpClient.send(fileReq, HttpResponse.BodyHandlers.ofString());

                            if (fileResp.statusCode() == 200) {
                                JsonNode blob = mapper.readTree(fileResp.body());
                                String base64Content = blob.path("content").asText();
                                String content = new String(java.util.Base64.getMimeDecoder().decode(base64Content.trim()), StandardCharsets.UTF_8);

                                String sourceFileId = connection.id() + ":" + repoName + "/" + path;
                                byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
                                ingestionService.ingest(
                                        sourceFileId,
                                        path,
                                        path,
                                        contentBytes,
                                        "text/markdown",
                                        connection.aclScope()
                                );
                            }
                        }
                    }
                }
            }

            count++;
            sink.progress(count * 100 / totalRepos, 100);
        }

        sink.progress(100, 100);
    }

    @Override
    public void registerWebhook(Connection connection) throws Exception {
        log.info("Webhook registration for GitHub is handled manually or auto-configured on webhook events");
    }

    @Override
    public void pollDelta(Connection connection) throws Exception {
        // Basic delta check - just run backfill again to update in place
        backfill(connection, (done, total) -> {});
    }

    @Override
    public void handleWebhookPayload(Connection connection, String rawPayload) throws Exception {
        log.info("Processing inbound GitHub webhook payload: {}", rawPayload);
        JsonNode payload = mapper.readTree(rawPayload);
        String action = payload.path("action").asText();
        String repoName = payload.path("repository").path("full_name").asText();

        if ("ping".equals(payload.path("zen").asText(null))) {
            log.info("GitHub webhook ping received successfully");
            return;
        }

        // Handle push event
        if (payload.has("commits")) {
            log.info("GitHub push event received for repository {}, refreshing contents", repoName);
            pollDelta(connection);
        }
    }

    private String getApiUrl(Connection connection, String path) {
        String base = connection.baseUrl();
        if (base == null || base.isBlank()) {
            base = "https://api.github.com";
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    private HttpRequest buildRequest(Connection connection, String url, String method, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");

        String secret = connection.authSecretEncrypted();
        if (secret != null && !secret.isBlank()) {
            String decrypted = credentialCipher.decrypt(secret);
            builder.header("Authorization", "Bearer " + decrypted);
        }

        if (method.equals("POST") || method.equals("PUT")) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body != null ? body : "", StandardCharsets.UTF_8));
            builder.header("Content-Type", "application/json");
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        return builder.build();
    }
}
