package com.mcpserver.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.services.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Confluence connector (Cloud + Server/Data Center). Fetched page content is written through
 * {@link IngestionService#ingest} — the same source-agnostic pipeline manual file upload uses —
 * so nothing downstream (chunking, embedding, search) needs to know this content came from
 * Confluence rather than an upload.
 *
 * <p>Deletion propagation via {@link #pollDelta} is a known gap: Confluence's content-search API
 * doesn't emit tombstones for deleted pages, only silently stops returning them, so delta polling
 * alone can't detect deletes. Only a Server/DC webhook's page-removed event (see
 * {@link #handleWebhookPayload}) purges reliably in this connector.
 */
@Component
public class ConfluenceConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceConnector.class);
    private static final int PAGE_SIZE = 25;
    private static final DateTimeFormatter CQL_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
            .withZone(ZoneOffset.UTC);

    private final IngestionService ingestionService;
    private final ConnectionRepository connectionRepository;
    private final CredentialCipher credentialCipher;
    private final String webhookBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConfluenceConnector(IngestionService ingestionService,
                                ConnectionRepository connectionRepository,
                                CredentialCipher credentialCipher,
                                @Value("${connectors.webhook-base-url:}") String webhookBaseUrl) {
        this.ingestionService = ingestionService;
        this.connectionRepository = connectionRepository;
        this.credentialCipher = credentialCipher;
        this.webhookBaseUrl = webhookBaseUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.CONFLUENCE;
    }

    @Override
    public DeploymentType detectDeployment(Connection connection) throws Exception {
        if (get(connection, DeploymentType.CLOUD, "/space?limit=1").statusCode() == 200) {
            return DeploymentType.CLOUD;
        }
        if (get(connection, DeploymentType.SERVER_DC, "/space?limit=1").statusCode() == 200) {
            return DeploymentType.SERVER_DC;
        }
        throw new IllegalStateException("Could not reach Confluence at " + connection.baseUrl()
                + " as either Cloud or Server/DC — check the base URL and credentials");
    }

    @Override
    public void testConnection(Connection connection) throws Exception {
        HttpResponse<String> res = get(connection, connection.deploymentType(), "/user/current");
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Confluence auth failed (HTTP " + res.statusCode() + "): "
                    + snippet(res.body()));
        }
    }

    @Override
    public void backfill(Connection connection, BackfillProgressSink sink) throws Exception {
        int start = 0;
        int processed = 0;
        while (true) {
            String path = "/content?type=page&status=current&expand=body.storage,version,space"
                    + "&start=" + start + "&limit=" + PAGE_SIZE;
            HttpResponse<String> res = get(connection, connection.deploymentType(), path);
            if (res.statusCode() != 200) {
                throw new IllegalStateException("Confluence backfill failed (HTTP " + res.statusCode() + "): "
                        + snippet(res.body()));
            }
            JsonNode results = mapper.readTree(res.body()).path("results");
            for (JsonNode page : results) {
                ingestPage(connection, page);
                processed++;
                sink.progress(processed, 0); // total page count isn't known upfront from this endpoint
            }
            if (results.size() < PAGE_SIZE) break;
            start += PAGE_SIZE;
        }
        connectionRepository.save(connectionRepository.findById(connection.id())
                .orElse(connection).withSyncCursor(Instant.now().toString()));
        log.info("Confluence backfill complete for connection {}: {} pages", connection.id(), processed);
    }

    @Override
    public void registerWebhook(Connection connection) throws Exception {
        if (connection.deploymentType() != DeploymentType.SERVER_DC) {
            throw new IllegalStateException("Cloud webhook registration requires a Connect/Forge app — relying on polling");
        }
        if (webhookBaseUrl == null || webhookBaseUrl.isBlank()) {
            throw new IllegalStateException("connectors.webhook-base-url is not configured — relying on polling");
        }
        String callbackUrl = webhookBaseUrl + "/api/connections/" + connection.id() + "/webhook";
        com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode();
        payload.put("name", "mcp-server-" + connection.id());
        payload.put("url", callbackUrl);
        payload.putArray("events").add("page_created").add("page_updated").add("page_removed");
        HttpResponse<String> res = post(connection, connection.deploymentType(), "/webhooks", payload.toString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("Webhook registration failed (HTTP " + res.statusCode() + "): "
                    + snippet(res.body()));
        }
        connectionRepository.save(connection.withWebhookRegistered(true));
    }

    @Override
    public void pollDelta(Connection connection) throws Exception {
        if (connection.syncCursor() == null) {
            // First tick with no prior backfill/poll: don't refetch the whole instance via an
            // unbounded CQL lower bound — just establish a cursor and let the next tick pick up
            // changes from here (backfill, if run, covers history).
            connectionRepository.save(connection.withSyncCursor(Instant.now().toString()));
            return;
        }
        String cursorCql = CQL_DATE.format(Instant.parse(connection.syncCursor()));
        String cql = "lastmodified >= \"" + cursorCql + "\" order by lastmodified asc";
        String path = "/content/search?cql=" + URLEncoder.encode(cql, StandardCharsets.UTF_8)
                + "&expand=body.storage,version,space&limit=" + PAGE_SIZE;
        HttpResponse<String> res = get(connection, connection.deploymentType(), path);
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Confluence delta poll failed (HTTP " + res.statusCode() + "): "
                    + snippet(res.body()));
        }
        JsonNode results = mapper.readTree(res.body()).path("results");
        String latestSeen = connection.syncCursor();
        for (JsonNode page : results) {
            ingestPage(connection, page);
            String when = page.path("version").path("when").asText(null);
            if (when != null) latestSeen = when;
        }
        if (!latestSeen.equals(connection.syncCursor())) {
            connectionRepository.save(connection.withSyncCursor(latestSeen));
        }
    }

    @Override
    public void handleWebhookPayload(Connection connection, String rawPayload) throws Exception {
        JsonNode body = mapper.readTree(rawPayload);
        JsonNode pageNode = body.has("page") ? body.path("page") : body.path("content");
        String pageId = pageNode.path("id").asText(null);
        if (pageId == null) {
            log.info("Confluence webhook payload for connection {} had no page/content id — ignoring", connection.id());
            return;
        }
        HttpResponse<String> res = get(connection, connection.deploymentType(),
                "/content/" + pageId + "?expand=body.storage,version,space");
        if (res.statusCode() == 404) {
            ingestionService.purgeSource(connection.id() + ":" + pageId);
            return;
        }
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Failed to refetch Confluence page " + pageId
                    + " after webhook (HTTP " + res.statusCode() + ")");
        }
        ingestPage(connection, mapper.readTree(res.body()));
    }

    private void ingestPage(Connection connection, JsonNode page) {
        String pageId = page.path("id").asText(null);
        String title = page.path("title").asText("Untitled");
        String spaceKey = page.path("space").path("key").asText(null);
        String storageValue = page.path("body").path("storage").path("value").asText("");
        if (pageId == null || storageValue.isBlank()) return;

        String webui = page.path("_links").path("webui").asText("");
        String base = page.path("_links").path("base").asText(connection.baseUrl());
        String url = webui.isBlank() ? null : base + webui;

        String whenStr = page.path("version").path("when").asText(null);
        Instant sourceUpdatedAt = whenStr == null ? null : Instant.parse(whenStr);

        List<String> aclTags = List.of(
                "connection:" + connection.id(),
                "confluence:space:" + (spaceKey == null ? "unknown" : spaceKey));

        ingestionService.ingest(
                connection.id() + ":" + pageId, title, spaceKey,
                storageValue.getBytes(StandardCharsets.UTF_8), "text/html", aclTags,
                "confluence", pageId, url, sourceUpdatedAt
        );
    }

    private String apiRoot(Connection connection, DeploymentType deployment) {
        return connection.baseUrl() + (deployment == DeploymentType.CLOUD ? "/wiki/rest/api" : "/rest/api");
    }

    private HttpResponse<String> get(Connection connection, DeploymentType deployment, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiRoot(connection, deployment) + path))
                .header("Authorization", AtlassianAuth.basicAuthHeader(connection.authUsername(),
                        credentialCipher.decrypt(connection.authSecretEncrypted())))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Connection connection, DeploymentType deployment, String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiRoot(connection, deployment) + path))
                .header("Authorization", AtlassianAuth.basicAuthHeader(connection.authUsername(),
                        credentialCipher.decrypt(connection.authSecretEncrypted())))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private String snippet(String body) {
        if (body == null) return "";
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }
}
