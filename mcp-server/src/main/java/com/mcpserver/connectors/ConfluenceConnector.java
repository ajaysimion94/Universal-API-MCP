package com.mcpserver.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.config.TlsHttpClientFactory;
import com.mcpserver.repositories.ChunkRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Confluence connector covering Cloud REST v2 and Server/Data Center REST v1. Delta polling
 * overlaps its cursor and a periodic lightweight inventory removes pages that were deleted or are
 * no longer visible to the connector account.
 */
@Component
public class ConfluenceConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceConnector.class);
    private static final int PAGE_SIZE = 25;
    private static final int INVENTORY_PAGE_SIZE = 250;
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\\\"next\\\"");
    private static final DateTimeFormatter CQL_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
            .withZone(ZoneOffset.UTC);

    private final IngestionService ingestionService;
    private final ChunkRepository chunkRepository;
    private final ConnectionRepository connectionRepository;
    private final CredentialCipher credentialCipher;
    private final WebhookTokenService webhookTokenService;
    private final String webhookBaseUrl;
    private final Duration reconcileInterval;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Instant> lastReconciledAt = new ConcurrentHashMap<>();

    public ConfluenceConnector(IngestionService ingestionService,
                                ChunkRepository chunkRepository,
                                ConnectionRepository connectionRepository,
                                CredentialCipher credentialCipher,
                                WebhookTokenService webhookTokenService,
                                TlsHttpClientFactory tlsHttpClientFactory,
                                @Value("${connectors.webhook-base-url:}") String webhookBaseUrl,
                                @Value("${connectors.reconcile-interval-ms:86400000}") long reconcileIntervalMs) {
        this.ingestionService = ingestionService;
        this.chunkRepository = chunkRepository;
        this.connectionRepository = connectionRepository;
        this.credentialCipher = credentialCipher;
        this.webhookTokenService = webhookTokenService;
        this.webhookBaseUrl = webhookBaseUrl;
        this.reconcileInterval = Duration.ofMillis(Math.max(60_000, reconcileIntervalMs));
        this.httpClient = tlsHttpClientFactory.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.CONFLUENCE;
    }

    @Override
    public DeploymentType detectDeployment(Connection connection) throws Exception {
        HttpResponse<String> cloud = get(connection, DeploymentType.CLOUD, "/spaces?limit=1");
        if (cloud.statusCode() == 200) return DeploymentType.CLOUD;

        HttpResponse<String> server = get(connection, DeploymentType.SERVER_DC, "/space?limit=1");
        if (server.statusCode() == 200) return DeploymentType.SERVER_DC;

        throw new IllegalStateException("Could not reach Confluence at " + connection.baseUrl()
                + " as Cloud (HTTP " + cloud.statusCode() + ") or Server/DC (HTTP "
                + server.statusCode() + ") — check the base URL and credentials");
    }

    @Override
    public void testConnection(Connection connection) throws Exception {
        String path = connection.deploymentType() == DeploymentType.CLOUD ? "/users/current" : "/user/current";
        HttpResponse<String> res = get(connection, connection.deploymentType(), path);
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Confluence auth failed (HTTP " + res.statusCode() + "): "
                    + snippet(res.body()));
        }
    }

    @Override
    public void backfill(Connection connection, BackfillProgressSink sink) throws Exception {
        Instant crawlStartedAt = Instant.now();
        Set<String> activePageIds = new HashSet<>();
        int processed = connection.deploymentType() == DeploymentType.CLOUD
                ? backfillCloud(connection, sink, activePageIds)
                : backfillServer(connection, sink, activePageIds);
        purgeMissing(connection, activePageIds);
        lastReconciledAt.put(connection.id(), Instant.now());
        connectionRepository.save(connectionRepository.findById(connection.id())
                .orElse(connection).withSyncCursor(crawlStartedAt.toString()));
        log.info("Confluence backfill complete for connection {}: {} pages", connection.id(), processed);
    }

    private int backfillCloud(Connection connection, BackfillProgressSink sink,
                              Set<String> activePageIds) throws Exception {
        String next = "/pages?status=current&body-format=storage&limit=" + PAGE_SIZE;
        int processed = 0;
        while (next != null) {
            HttpResponse<String> res = get(connection, DeploymentType.CLOUD, next);
            if (res.statusCode() != 200) throw fetchFailure("backfill", res);
            JsonNode response = mapper.readTree(res.body());
            String responseBase = response.path("_links").path("base").asText(null);
            for (JsonNode page : response.path("results")) {
                String pageId = page.path("id").asText(null);
                if (pageId != null) activePageIds.add(pageId);
                ingestPage(connection, page, responseBase);
                sink.progress(++processed, 0);
            }
            next = cloudNext(res, response);
        }
        return processed;
    }

    private int backfillServer(Connection connection, BackfillProgressSink sink,
                               Set<String> activePageIds) throws Exception {
        int start = 0;
        int processed = 0;
        while (true) {
            String path = "/content?type=page&status=current&expand=body.storage,version,space"
                    + "&start=" + start + "&limit=" + PAGE_SIZE;
            HttpResponse<String> res = get(connection, DeploymentType.SERVER_DC, path);
            if (res.statusCode() != 200) throw fetchFailure("backfill", res);
            JsonNode response = mapper.readTree(res.body());
            JsonNode results = response.path("results");
            String responseBase = response.path("_links").path("base").asText(null);
            for (JsonNode page : results) {
                String pageId = page.path("id").asText(null);
                if (pageId != null) activePageIds.add(pageId);
                ingestPage(connection, page, responseBase);
                sink.progress(++processed, 0);
            }
            if (results.size() < PAGE_SIZE) break;
            start += results.size();
        }
        return processed;
    }

    @Override
    public void registerWebhook(Connection connection) throws Exception {
        if (connection.deploymentType() != DeploymentType.SERVER_DC) {
            throw new IllegalStateException("Cloud webhook registration requires a Connect/Forge app — relying on polling");
        }
        if (webhookBaseUrl == null || webhookBaseUrl.isBlank()) {
            throw new IllegalStateException("connectors.webhook-base-url is not configured — relying on polling");
        }
        String token = webhookTokenService.getOrCreate(connection.id());
        String callbackUrl = webhookBaseUrl + "/api/connections/" + connection.id()
                + "/webhook?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode();
        payload.put("name", "mcp-server-" + connection.id());
        payload.put("url", callbackUrl);
        payload.putArray("events").add("page_created").add("page_updated").add("page_removed");
        HttpResponse<String> res = post(connection, DeploymentType.SERVER_DC, "/webhooks", payload.toString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("Webhook registration failed (HTTP " + res.statusCode() + "): "
                    + snippet(res.body()));
        }
        connectionRepository.save(connection.withWebhookRegistered(true));
    }

    @Override
    public void pollDelta(Connection connection) throws Exception {
        if (connection.syncCursor() == null) {
            connectionRepository.save(connection.withSyncCursor(Instant.now().toString()));
            maybeReconcile(connection);
            return;
        }
        String latestSeen = connection.deploymentType() == DeploymentType.CLOUD
                ? pollCloudDelta(connection) : pollServerDelta(connection);
        if (!latestSeen.equals(connection.syncCursor())) {
            connectionRepository.save(connection.withSyncCursor(latestSeen));
        }
        maybeReconcile(connection);
    }

    private String pollCloudDelta(Connection connection) throws Exception {
        Instant cursor = Instant.parse(connection.syncCursor());
        Instant latest = cursor;
        String next = "/pages?status=current&body-format=storage&sort=-modified-date&limit=" + PAGE_SIZE;
        boolean reachedCursor = false;
        while (next != null && !reachedCursor) {
            HttpResponse<String> res = get(connection, DeploymentType.CLOUD, next);
            if (res.statusCode() != 200) throw fetchFailure("delta poll", res);
            JsonNode response = mapper.readTree(res.body());
            String responseBase = response.path("_links").path("base").asText(null);
            for (JsonNode page : response.path("results")) {
                Instant updated = pageUpdatedAt(page);
                if (updated != null && updated.isBefore(cursor)) {
                    reachedCursor = true;
                    break;
                }
                ingestPage(connection, page, responseBase);
                if (updated != null && updated.isAfter(latest)) latest = updated;
            }
            next = reachedCursor ? null : cloudNext(res, response);
        }
        return latest.toString();
    }

    private String pollServerDelta(Connection connection) throws Exception {
        String cursorCql = CQL_DATE.format(Instant.parse(connection.syncCursor()));
        String cql = "lastmodified >= \"" + cursorCql + "\" order by lastmodified asc";
        Instant latest = Instant.parse(connection.syncCursor());
        int start = 0;
        while (true) {
            String path = "/content/search?cql=" + URLEncoder.encode(cql, StandardCharsets.UTF_8)
                    + "&expand=body.storage,version,space&start=" + start + "&limit=" + PAGE_SIZE;
            HttpResponse<String> res = get(connection, DeploymentType.SERVER_DC, path);
            if (res.statusCode() != 200) throw fetchFailure("delta poll", res);
            JsonNode response = mapper.readTree(res.body());
            JsonNode results = response.path("results");
            String responseBase = response.path("_links").path("base").asText(null);
            for (JsonNode page : results) {
                ingestPage(connection, page, responseBase);
                Instant updated = pageUpdatedAt(page);
                if (updated != null && updated.isAfter(latest)) latest = updated;
            }
            int returned = results.size();
            boolean hasNext = !response.path("_links").path("next").asText("").isBlank();
            if (returned == 0 || (!hasNext && returned < PAGE_SIZE)) break;
            start += returned;
        }
        return latest.toString();
    }

    private void maybeReconcile(Connection connection) throws Exception {
        Instant now = Instant.now();
        Instant last = lastReconciledAt.get(connection.id());
        if (last != null && last.plus(reconcileInterval).isAfter(now)) return;
        purgeMissing(connection, fetchActivePageIds(connection));
        lastReconciledAt.put(connection.id(), now);
    }

    private Set<String> fetchActivePageIds(Connection connection) throws Exception {
        Set<String> ids = new HashSet<>();
        if (connection.deploymentType() == DeploymentType.CLOUD) {
            String next = "/pages?status=current&limit=" + INVENTORY_PAGE_SIZE;
            while (next != null) {
                HttpResponse<String> res = get(connection, DeploymentType.CLOUD, next);
                if (res.statusCode() != 200) throw fetchFailure("reconciliation", res);
                JsonNode response = mapper.readTree(res.body());
                for (JsonNode page : response.path("results")) {
                    String id = page.path("id").asText(null);
                    if (id != null) ids.add(id);
                }
                next = cloudNext(res, response);
            }
        } else {
            int start = 0;
            while (true) {
                HttpResponse<String> res = get(connection, DeploymentType.SERVER_DC,
                        "/content?type=page&status=current&start=" + start + "&limit=" + PAGE_SIZE);
                if (res.statusCode() != 200) throw fetchFailure("reconciliation", res);
                JsonNode results = mapper.readTree(res.body()).path("results");
                for (JsonNode page : results) {
                    String id = page.path("id").asText(null);
                    if (id != null) ids.add(id);
                }
                if (results.size() < PAGE_SIZE) break;
                start += results.size();
            }
        }
        return ids;
    }

    private void purgeMissing(Connection connection, Set<String> activePageIds) {
        Set<String> stale = chunkRepository.findExternalIds(connection.id(), "confluence");
        stale.removeAll(activePageIds);
        stale.forEach(id -> ingestionService.purgeSource(connection.id() + ":" + id));
        if (!stale.isEmpty()) {
            log.info("Confluence reconciliation purged {} stale page(s) for connection {}",
                    stale.size(), connection.id());
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
        String path = connection.deploymentType() == DeploymentType.CLOUD
                ? "/pages/" + pageId + "?body-format=storage"
                : "/content/" + pageId + "?expand=body.storage,version,space";
        HttpResponse<String> res = get(connection, connection.deploymentType(), path);
        if (res.statusCode() == 404) {
            ingestionService.purgeSource(connection.id() + ":" + pageId);
            return;
        }
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Failed to refetch Confluence page " + pageId
                    + " after webhook (HTTP " + res.statusCode() + ")");
        }
        JsonNode response = mapper.readTree(res.body());
        ingestPage(connection, response, response.path("_links").path("base").asText(null));
    }

    private void ingestPage(Connection connection, JsonNode page, String responseBase) {
        String pageId = page.path("id").asText(null);
        if (pageId == null) return;
        String title = page.path("title").asText("Untitled");
        String space = page.path("space").path("key").asText(page.path("spaceId").asText(null));
        String storageValue = page.path("body").path("storage").path("value").asText("");

        String webui = page.path("_links").path("webui").asText("");
        String base = responseBase;
        if (base == null || base.isBlank()) {
            base = connection.baseUrl() + (connection.deploymentType() == DeploymentType.CLOUD ? "/wiki" : "");
        }
        String url = webui.isBlank() ? null : joinUrl(base, webui);
        Instant sourceUpdatedAt = pageUpdatedAt(page);

        List<String> aclTags = List.of(
                "connection:" + connection.id(),
                "confluence:space:" + (space == null ? "unknown" : space));

        ingestionService.ingest(
                connection.id() + ":" + pageId, title, space,
                storageValue.getBytes(StandardCharsets.UTF_8), "text/html", aclTags,
                "confluence", pageId, url, sourceUpdatedAt
        );
    }

    private Instant pageUpdatedAt(JsonNode page) {
        String value = page.path("version").path("createdAt").asText(
                page.path("version").path("when").asText(null));
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private String cloudNext(HttpResponse<String> res, JsonNode response) {
        String next = response.path("_links").path("next").asText("");
        if (!next.isBlank()) return next;
        String link = res.headers().firstValue("Link").orElse("");
        Matcher matcher = NEXT_LINK.matcher(link);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String apiRoot(Connection connection, DeploymentType deployment) {
        return connection.baseUrl() + (deployment == DeploymentType.CLOUD ? "/wiki/api/v2" : "/rest/api");
    }

    private HttpResponse<String> get(Connection connection, DeploymentType deployment, String path) throws Exception {
        return send(connection, deployment, path, null);
    }

    private HttpResponse<String> post(Connection connection, DeploymentType deployment,
                                      String path, String jsonBody) throws Exception {
        return send(connection, deployment, path, jsonBody);
    }

    private HttpResponse<String> send(Connection connection, DeploymentType deployment,
                                      String path, String jsonBody) throws Exception {
        String target = path.startsWith("http://") || path.startsWith("https://")
                ? path
                : path.startsWith("/wiki/") || path.startsWith("/rest/")
                    ? connection.baseUrl() + path
                    : apiRoot(connection, deployment) + path;
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(target))
                .header("Authorization", AtlassianAuth.authorizationHeader(connection.authMode(),
                        connection.authUsername(), credentialCipher.decrypt(connection.authSecretEncrypted())))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20));
        if (jsonBody == null) {
            req.GET();
        } else {
            req.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        return httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private IllegalStateException fetchFailure(String operation, HttpResponse<String> res) {
        return new IllegalStateException("Confluence " + operation + " failed (HTTP "
                + res.statusCode() + "): " + snippet(res.body()));
    }

    private static String joinUrl(String base, String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        return base.replaceAll("/$", "") + "/" + path.replaceAll("^/", "");
    }

    private String snippet(String body) {
        if (body == null) return "";
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }
}
