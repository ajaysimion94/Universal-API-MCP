package com.mcpserver.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.config.TlsHttpClientFactory;
import com.mcpserver.rag.retrieval.TextSignals;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    private final SourceCatalogRepository catalogRepository;
    private final ConnectionRepository connectionRepository;
    private final CredentialCipher credentialCipher;
    private final WebhookTokenService webhookTokenService;
    private final String webhookBaseUrl;
    private final Duration reconcileInterval;
    private final Duration remoteDiscoveryTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Instant> lastReconciledAt = new ConcurrentHashMap<>();

    public ConfluenceConnector(IngestionService ingestionService,
                                SourceCatalogRepository catalogRepository,
                                ConnectionRepository connectionRepository,
                                CredentialCipher credentialCipher,
                                WebhookTokenService webhookTokenService,
                                TlsHttpClientFactory tlsHttpClientFactory,
                                @Value("${connectors.webhook-base-url:}") String webhookBaseUrl,
                                @Value("${connectors.reconcile-interval-ms:86400000}") long reconcileIntervalMs,
                                @Value("${connectors.remote-discovery-timeout-ms:5000}") long remoteDiscoveryTimeoutMs) {
        this.ingestionService = ingestionService;
        this.catalogRepository = catalogRepository;
        this.connectionRepository = connectionRepository;
        this.credentialCipher = credentialCipher;
        this.webhookTokenService = webhookTokenService;
        this.webhookBaseUrl = webhookBaseUrl;
        this.reconcileInterval = Duration.ofMillis(Math.max(60_000, reconcileIntervalMs));
        this.remoteDiscoveryTimeout = Duration.ofMillis(Math.max(1_000, remoteDiscoveryTimeoutMs));
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
        // A reverse proxy can return a normal 404 for the wrong deployment path. Prefer an
        // authentication/permission response from either probe, otherwise return one stable,
        // body-free diagnostic rather than exposing both remote response details.
        if (cloud.statusCode() == 401 || server.statusCode() == 401) {
            throw ConnectorException.forHttp("Confluence", "deployment detection", 401);
        }
        if (cloud.statusCode() == 403 || server.statusCode() == 403) {
            throw ConnectorException.forHttp("Confluence", "deployment detection", 403);
        }
        int status = cloud.statusCode() >= 500 ? cloud.statusCode() : server.statusCode();
        throw ConnectorException.forHttp("Confluence", "deployment detection", status);
    }

    @Override
    public void testConnection(Connection connection) throws Exception {
        String path = connection.deploymentType() == DeploymentType.CLOUD ? "/users/current" : "/user/current";
        HttpResponse<String> res = get(connection, connection.deploymentType(), path);
        if (res.statusCode() != 200) {
            throw ConnectorException.forHttp("Confluence", "authentication", res.statusCode());
        }
    }

    @Override
    public void verifyReadAccess(Connection connection) throws Exception {
        String path = connection.deploymentType() == DeploymentType.CLOUD
                ? "/pages?status=current&limit=1"
                : "/content?type=page&status=current&limit=1";
        HttpResponse<String> response = get(connection, connection.deploymentType(), path);
        if (response.statusCode() != 200) {
            throw ConnectorException.forHttp("Confluence", "read access check", response.statusCode());
        }
    }

    @Override
    public List<CatalogResource> discover(Connection connection, String query, int limit) throws Exception {
        String cql = discoveryCql(query);
        if (cql == null) return List.of();
        int safeLimit = Math.max(1, Math.min(limit, 3));
        String searchPath = (connection.deploymentType() == DeploymentType.CLOUD ? "/wiki" : "")
                + "/rest/api/content/search?cql=" + URLEncoder.encode(cql, StandardCharsets.UTF_8)
                + "&limit=" + safeLimit + "&expand=version,space";
        HttpResponse<String> response = getDiscovery(connection, connection.deploymentType(), searchPath);
        if (response.statusCode() != 200) {
            throw ConnectorException.forHttp("Confluence", "content discovery", response.statusCode());
        }
        List<CatalogResource> discovered = new ArrayList<>();
        JsonNode results = mapper.readTree(response.body()).path("results");
        for (JsonNode page : results) {
            if (discovered.size() >= safeLimit) break;
            String pageId = page.path("id").asText(null);
            if (pageId == null || pageId.isBlank()) continue;
            catalogPage(connection, page, page.path("_links").path("base").asText(null));
            catalogRepository.find(connection.id(), "confluence", pageId).ifPresent(discovered::add);
        }
        return discovered;
    }

    @Override
    public void backfill(Connection connection, BackfillProgressSink sink) throws Exception {
        Instant crawlStartedAt = Instant.now();
        catalogSpaces(connection);
        catalogRepository.beginInventory(connection.id(), "confluence");
        int processed;
        try {
            processed = connection.deploymentType() == DeploymentType.CLOUD
                    ? backfillCloud(connection, sink)
                    : backfillServer(connection, sink);
            purgeMissing(connection);
        } finally {
            catalogRepository.clearInventory(connection.id(), "confluence");
        }
        lastReconciledAt.put(connection.id(), Instant.now());
        connectionRepository.save(connectionRepository.findById(connection.id())
                .orElse(connection).withSyncCursor(crawlStartedAt.toString()));
        log.info("Confluence backfill complete for connection {}: {} pages", connection.id(), processed);
    }

    private int backfillCloud(Connection connection, BackfillProgressSink sink) throws Exception {
        String next = "/pages?status=current&limit=" + PAGE_SIZE;
        int processed = 0;
        while (next != null) {
            HttpResponse<String> res = get(connection, DeploymentType.CLOUD, next);
            if (res.statusCode() != 200) throw fetchFailure("backfill", res);
            JsonNode response = mapper.readTree(res.body());
            String responseBase = response.path("_links").path("base").asText(null);
            for (JsonNode page : response.path("results")) {
                String pageId = page.path("id").asText(null);
                catalogRepository.recordInventoryId(connection.id(), "confluence", pageId);
                catalogPage(connection, page, responseBase);
                sink.progress(++processed, 0);
            }
            next = cloudNext(res, response);
        }
        return processed;
    }

    private int backfillServer(Connection connection, BackfillProgressSink sink) throws Exception {
        int start = 0;
        int processed = 0;
        while (true) {
            String path = "/content?type=page&status=current&expand=version,space"
                    + "&start=" + start + "&limit=" + PAGE_SIZE;
            HttpResponse<String> res = get(connection, DeploymentType.SERVER_DC, path);
            if (res.statusCode() != 200) throw fetchFailure("backfill", res);
            JsonNode response = mapper.readTree(res.body());
            JsonNode results = response.path("results");
            String responseBase = response.path("_links").path("base").asText(null);
            for (JsonNode page : results) {
                String pageId = page.path("id").asText(null);
                catalogRepository.recordInventoryId(connection.id(), "confluence", pageId);
                catalogPage(connection, page, responseBase);
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
            throw ConnectorException.forHttp("Confluence", "webhook registration", res.statusCode());
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
        String next = "/pages?status=current&sort=-modified-date&limit=" + PAGE_SIZE;
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
                catalogPage(connection, page, responseBase);
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
                    + "&expand=version,space&start=" + start + "&limit=" + PAGE_SIZE;
            HttpResponse<String> res = get(connection, DeploymentType.SERVER_DC, path);
            if (res.statusCode() != 200) throw fetchFailure("delta poll", res);
            JsonNode response = mapper.readTree(res.body());
            JsonNode results = response.path("results");
            String responseBase = response.path("_links").path("base").asText(null);
            for (JsonNode page : results) {
                catalogPage(connection, page, responseBase);
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
        catalogRepository.beginInventory(connection.id(), "confluence");
        try {
            scanActivePageIds(connection);
            purgeMissing(connection);
            lastReconciledAt.put(connection.id(), now);
        } finally {
            catalogRepository.clearInventory(connection.id(), "confluence");
        }
    }

    private void scanActivePageIds(Connection connection) throws Exception {
        if (connection.deploymentType() == DeploymentType.CLOUD) {
            String next = "/pages?status=current&limit=" + INVENTORY_PAGE_SIZE;
            while (next != null) {
                HttpResponse<String> res = get(connection, DeploymentType.CLOUD, next);
                if (res.statusCode() != 200) throw fetchFailure("reconciliation", res);
                JsonNode response = mapper.readTree(res.body());
                for (JsonNode page : response.path("results")) {
                    String id = page.path("id").asText(null);
                    catalogRepository.recordInventoryId(connection.id(), "confluence", id);
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
                    catalogRepository.recordInventoryId(connection.id(), "confluence", id);
                }
                if (results.size() < PAGE_SIZE) break;
                start += results.size();
            }
        }
    }

    private void purgeMissing(Connection connection) {
        Set<String> stale = catalogRepository.findMissingFromInventory(connection.id(), "confluence");
        stale.forEach(id -> {
            catalogRepository.deleteResource(connection.id(), "confluence", id);
            ingestionService.purgeSource(connection.id() + ":" + id);
        });
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
                ? "/pages/" + pageId
                : "/content/" + pageId + "?expand=version,space";
        HttpResponse<String> res = get(connection, connection.deploymentType(), path);
        if (res.statusCode() == 404) {
            catalogRepository.deleteResource(connection.id(), "confluence", pageId);
            ingestionService.purgeSource(connection.id() + ":" + pageId);
            return;
        }
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Failed to refetch Confluence page " + pageId
                    + " after webhook (HTTP " + res.statusCode() + ")");
        }
        JsonNode response = mapper.readTree(res.body());
        catalogPage(connection, response, response.path("_links").path("base").asText(null));
    }

    @Override
    public void hydrate(Connection connection, CatalogResource resource) throws Exception {
        HttpResponse<String> res = get(connection, connection.deploymentType(), resource.apiPath());
        if (res.statusCode() == 404) {
            catalogRepository.deleteResource(connection.id(), "confluence", resource.externalId());
            ingestionService.purgeSource(connection.id() + ":" + resource.externalId());
            return;
        }
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Failed to lazily fetch Confluence page "
                    + resource.externalId() + " (HTTP " + res.statusCode() + ")");
        }
        JsonNode page = mapper.readTree(res.body());
        ingestPage(connection, page, page.path("_links").path("base").asText(null));
    }

    private void catalogPage(Connection connection, JsonNode page, String responseBase) {
        String pageId = page.path("id").asText(null);
        if (pageId == null) return;
        String title = page.path("title").asText("Untitled");
        String space = page.path("space").path("key").asText(page.path("spaceId").asText(null));
        String containerName = catalogRepository.findContainerName(connection.id(), "confluence", space)
                .orElse(space == null ? "Unknown space" : space);
        catalogRepository.upsertContainer(connection.id(), "confluence", space, containerName, null);

        String webui = page.path("_links").path("webui").asText("");
        String base = responseBase;
        if (base == null || base.isBlank()) {
            base = connection.baseUrl() + (connection.deploymentType() == DeploymentType.CLOUD ? "/wiki" : "");
        }
        String url = webui.isBlank() ? null : joinUrl(base, webui);
        String apiPath = connection.deploymentType() == DeploymentType.CLOUD
                ? "/pages/" + pageId + "?body-format=storage"
                : "/content/" + pageId + "?expand=body.storage,version,space";
        SourceCatalogRepository.UpsertResult result = catalogRepository.upsertResource(
                connection.id(), "confluence", pageId, space, containerName, title,
                apiPath, url, pageUpdatedAt(page));
        if (result.invalidatedIndexedContent()) {
            ingestionService.purgeSource(connection.id() + ":" + pageId);
        }
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

        List<String> aclTags = new ArrayList<>(connection.aclScope());
        aclTags.add("connection:" + connection.id());
        aclTags.add("confluence:space:" + (space == null ? "unknown" : space));

        ingestionService.ingest(
                connection.id() + ":" + pageId, title, space,
                storageValue.getBytes(StandardCharsets.UTF_8), "text/html", aclTags,
                "confluence", pageId, url, sourceUpdatedAt
        );
    }

    private void catalogSpaces(Connection connection) {
        try {
            if (connection.deploymentType() == DeploymentType.CLOUD) {
                String next = "/spaces?limit=" + INVENTORY_PAGE_SIZE;
                while (next != null) {
                    HttpResponse<String> res = get(connection, DeploymentType.CLOUD, next);
                    if (res.statusCode() != 200) return;
                    JsonNode response = mapper.readTree(res.body());
                    String responseBase = response.path("_links").path("base").asText(null);
                    for (JsonNode space : response.path("results")) {
                        String id = space.path("id").asText(space.path("key").asText(null));
                        String name = space.path("name").asText(space.path("key").asText(id));
                        String webui = space.path("_links").path("webui").asText("");
                        String url = webui.isBlank() ? null : joinUrl(
                                responseBase == null ? connection.baseUrl() + "/wiki" : responseBase, webui);
                        catalogRepository.upsertContainer(connection.id(), "confluence", id, name, url);
                    }
                    next = cloudNext(res, response);
                }
            } else {
                int start = 0;
                while (true) {
                    HttpResponse<String> res = get(connection, DeploymentType.SERVER_DC,
                            "/space?start=" + start + "&limit=" + INVENTORY_PAGE_SIZE);
                    if (res.statusCode() != 200) return;
                    JsonNode results = mapper.readTree(res.body()).path("results");
                    for (JsonNode space : results) {
                        String key = space.path("key").asText(null);
                        catalogRepository.upsertContainer(connection.id(), "confluence", key,
                                space.path("name").asText(key), null);
                    }
                    if (results.size() < INVENTORY_PAGE_SIZE) break;
                    start += results.size();
                }
            }
        } catch (Exception e) {
            // Page metadata still carries a space id/key, so a space-list failure degrades to that
            // identifier instead of failing a potentially very large catalogue crawl.
            log.warn("Could not catalogue Confluence spaces for {}: {}", connection.id(), e.getMessage());
        }
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

    private HttpResponse<String> getDiscovery(Connection connection, DeploymentType deployment, String path) throws Exception {
        return send(connection, deployment, path, null, remoteDiscoveryTimeout, true);
    }

    private HttpResponse<String> post(Connection connection, DeploymentType deployment,
                                      String path, String jsonBody) throws Exception {
        return send(connection, deployment, path, jsonBody);
    }

    private HttpResponse<String> send(Connection connection, DeploymentType deployment,
                                      String path, String jsonBody) throws Exception {
        return send(connection, deployment, path, jsonBody, Duration.ofSeconds(20), false);
    }

    private HttpResponse<String> send(Connection connection, DeploymentType deployment,
                                      String path, String jsonBody, Duration timeout,
                                      boolean retryTransientFailure) throws Exception {
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
                .timeout(timeout);
        if (jsonBody == null) {
            req.GET();
        } else {
            req.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        return sendWithRetry(req.build(), retryTransientFailure);
    }

    private IllegalStateException fetchFailure(String operation, HttpResponse<String> res) {
        return ConnectorException.forHttp("Confluence", operation, res.statusCode());
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request, boolean retryTransientFailure) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (!retryTransientFailure || attempt > 0 || !isTransient(response.statusCode())) return response;
                waitBeforeRetry(response);
            } catch (java.io.IOException exception) {
                if (!retryTransientFailure || attempt > 0) {
                    throw new ConnectorException(ConnectorFailureCategory.UNREACHABLE,
                            "Confluence request could not reach the source", exception);
                }
                Thread.sleep(200);
            }
        }
    }

    private static boolean isTransient(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static void waitBeforeRetry(HttpResponse<?> response) throws InterruptedException {
        String retryAfter = response.headers().firstValue("Retry-After").orElse("");
        long waitMillis = 200;
        try {
            waitMillis = Math.min(5_000L, Math.max(0L, Long.parseLong(retryAfter.trim()) * 1_000L));
        } catch (NumberFormatException ignored) {
            // A malformed header falls back to the short transient-error delay.
        }
        Thread.sleep(waitMillis);
    }

    private static String discoveryCql(String query) {
        TreeSet<String> terms = new TreeSet<>(TextSignals.terms(query));
        if (terms.isEmpty()) return null;
        String phrase = String.join(" ", terms.stream().limit(5).toList());
        return "type = page AND text ~ \"" + phrase + "\"";
    }

    private static String joinUrl(String base, String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        return base.replaceAll("/$", "") + "/" + path.replaceAll("^/", "");
    }

}
