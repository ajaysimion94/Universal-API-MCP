package com.mcpserver.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.config.TlsHttpClientFactory;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jira connector (Cloud + Server/Data Center). Issue search has two incompatible generations:
 * the classic {@code GET /rest/api/2/search} (offset pagination via {@code startAt}) that every
 * Jira since ~2012 supports, and {@code POST /rest/api/3/search/jql} (cursor pagination via
 * {@code nextPageToken}) that Atlassian made mandatory on Cloud after retiring the classic
 * endpoint there in 2025 (it now returns 410 Gone on Cloud). Server/Data Center — which isn't on
 * Atlassian's forced migration schedule — generally still only exposes the classic endpoint.
 * {@link #searchIssues} tries the modern endpoint first and falls back to the classic one on
 * 404/410, caching the result per connection so steady-state polling doesn't re-probe every call.
 * This is what lets one connector cover both a fresh Cloud tenant and a Server/DC instance from
 * roughly 2015 onward without needing to know which in advance.
 *
 * <p>Fetched issues are rendered to markdown (heading per field group) and written through
 * {@link IngestionService#ingest}, reusing the existing heading-aware {@code Chunker} rather than
 * a bespoke ticket-structured one.
 */
@Component
public class JiraConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(JiraConnector.class);
    private static final int PAGE_SIZE = 25;
    private static final List<String> FIELDS_LIST = List.of(
            "summary", "description", "status", "assignee", "priority", "labels", "comment", "updated", "project");
    private static final List<String> CATALOG_FIELDS = List.of("summary", "updated", "project");
    private static final String FIELDS_CSV = String.join(",", FIELDS_LIST);
    private static final DateTimeFormatter JQL_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
            .withZone(ZoneOffset.UTC);

    private final IngestionService ingestionService;
    private final SourceCatalogRepository catalogRepository;
    private final ConnectionRepository connectionRepository;
    private final CredentialCipher credentialCipher;
    private final WebhookTokenService webhookTokenService;
    private final String webhookBaseUrl;
    private final Duration reconcileInterval;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Per-connection cache of which search endpoint generation works, populated on first use. */
    private final Map<String, Boolean> modernSearchSupported = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastReconciledAt = new ConcurrentHashMap<>();

    public JiraConnector(IngestionService ingestionService,
                          SourceCatalogRepository catalogRepository,
                          ConnectionRepository connectionRepository,
                          CredentialCipher credentialCipher,
                          WebhookTokenService webhookTokenService,
                          TlsHttpClientFactory tlsHttpClientFactory,
                          @Value("${connectors.webhook-base-url:}") String webhookBaseUrl,
                          @Value("${connectors.reconcile-interval-ms:86400000}") long reconcileIntervalMs) {
        this.ingestionService = ingestionService;
        this.catalogRepository = catalogRepository;
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
        return ConnectionType.JIRA;
    }

    @Override
    public DeploymentType detectDeployment(Connection connection) throws Exception {
        HttpResponse<String> res = get(connection, "/serverInfo");
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Could not reach Jira at " + connection.baseUrl()
                    + " (HTTP " + res.statusCode() + ") — check the base URL and credentials");
        }
        String deploymentType = mapper.readTree(res.body()).path("deploymentType").asText("");
        return "Cloud".equalsIgnoreCase(deploymentType) ? DeploymentType.CLOUD : DeploymentType.SERVER_DC;
    }

    @Override
    public void testConnection(Connection connection) throws Exception {
        HttpResponse<String> res = get(connection, "/myself");
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Jira auth failed (HTTP " + res.statusCode() + "): "
                    + snippet(res.body()));
        }
    }

    @Override
    public void backfill(Connection connection, BackfillProgressSink sink) throws Exception {
        Instant crawlStartedAt = Instant.now();
        catalogRepository.beginInventory(connection.id(), "jira");
        String cursor = null;
        int processed = 0;
        try {
            do {
                IssuePage page = searchIssues(connection, "order by updated asc", cursor, PAGE_SIZE, CATALOG_FIELDS);
                for (JsonNode issue : page.issues()) {
                    String key = issue.path("key").asText(null);
                    catalogRepository.recordInventoryId(connection.id(), "jira", key);
                    catalogIssue(connection, issue);
                    processed++;
                    sink.progress(processed, 0); // neither endpoint gives a reliable upfront total
                }
                cursor = page.nextCursor();
            } while (cursor != null);
            purgeMissing(connection);
        } finally {
            catalogRepository.clearInventory(connection.id(), "jira");
        }
        lastReconciledAt.put(connection.id(), Instant.now());
        connectionRepository.save(connectionRepository.findById(connection.id())
                .orElse(connection).withSyncCursor(crawlStartedAt.toString()));
        log.info("Jira backfill complete for connection {}: {} issues", connection.id(), processed);
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
        payload.putArray("events").add("jira:issue_created").add("jira:issue_updated").add("jira:issue_deleted");
        HttpResponse<String> res = post(connection, "/rest", "/webhooks/1.0/webhook", payload.toString());
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
        String cursorJql = JQL_DATE.format(Instant.parse(connection.syncCursor()));
        String jql = "updated >= \"" + cursorJql + "\" order by updated asc";
        String latestSeen = connection.syncCursor();
        String pageCursor = null;
        do {
            IssuePage page = searchIssues(connection, jql, pageCursor, PAGE_SIZE, CATALOG_FIELDS);
            for (JsonNode issue : page.issues()) {
                catalogIssue(connection, issue);
                String updated = issue.path("fields").path("updated").asText(null);
                if (updated != null) {
                    String normalized = normalizeJiraTimestamp(updated);
                    if (Instant.parse(normalized).isAfter(Instant.parse(latestSeen))) latestSeen = normalized;
                }
            }
            pageCursor = page.nextCursor();
        } while (pageCursor != null);
        if (!latestSeen.equals(connection.syncCursor())) {
            connectionRepository.save(connection.withSyncCursor(latestSeen));
        }
        maybeReconcile(connection);
    }

    private record IssuePage(List<JsonNode> issues, String nextCursor) {}

    /**
     * Tries {@code POST /rest/api/3/search/jql} (mandatory on current Cloud) first; on 404/410
     * (endpoint doesn't exist — typical Server/DC) falls back to the classic
     * {@code GET /rest/api/2/search}, encoding {@code startAt} as the cursor string. The choice is
     * cached per connection in {@link #modernSearchSupported} so only the very first call pays for
     * the failed probe.
     */
    private IssuePage searchIssues(Connection connection, String jql, String cursor, int maxResults) throws Exception {
        return searchIssues(connection, jql, cursor, maxResults, FIELDS_LIST);
    }

    private IssuePage searchIssues(Connection connection, String jql, String cursor,
                                   int maxResults, List<String> fields) throws Exception {
        String capabilityKey = connection.id() + "\u0000" + connection.baseUrl();
        Boolean modernOk = modernSearchSupported.get(capabilityKey);
        boolean firstProbe = modernOk == null;
        if (modernOk == null || modernOk) {
            HttpResponse<String> res = postSearchJql(connection, jql, cursor, maxResults, fields);
            if (res.statusCode() == 200) {
                modernSearchSupported.put(capabilityKey, true);
                JsonNode body = mapper.readTree(res.body());
                List<JsonNode> issues = toList(body.path("issues"));
                String nextToken = body.path("nextPageToken").asText(null);
                String next = (nextToken == null || nextToken.isBlank() || issues.isEmpty()) ? null : nextToken;
                return new IssuePage(issues, next);
            }
            boolean endpointUnavailable = res.statusCode() == 404 || res.statusCode() == 410;
            // Only on the very first probe for a connection do we also treat 400 as "this
            // deployment doesn't speak the modern request shape" (the exact shape was verified
            // against secondhand community reports, not Atlassian's own reference doc, so a wrong
            // assumption here is a real possibility). Once modern is confirmed working for a
            // connection, a later 400 is treated as a genuine request error (e.g. malformed JQL)
            // and surfaces rather than being silently swallowed by falling back.
            boolean unrecognizedShapeOnFirstProbe = firstProbe && res.statusCode() == 400;
            if (endpointUnavailable || unrecognizedShapeOnFirstProbe) {
                modernSearchSupported.put(capabilityKey, false);
            } else {
                throw new IllegalStateException("Jira search failed (HTTP " + res.statusCode() + "): " + snippet(res.body()));
            }
        }
        int startAt = cursor == null ? 0 : Integer.parseInt(cursor);
        HttpResponse<String> res = getLegacySearch(connection, jql, startAt, maxResults, fields);
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Jira search failed (HTTP " + res.statusCode() + "): " + snippet(res.body()));
        }
        JsonNode body = mapper.readTree(res.body());
        List<JsonNode> issues = toList(body.path("issues"));
        int newStartAt = startAt + issues.size();
        int total = body.path("total").asInt(newStartAt);
        String next = (issues.isEmpty() || newStartAt >= total) ? null : String.valueOf(newStartAt);
        return new IssuePage(issues, next);
    }

    private HttpResponse<String> postSearchJql(Connection connection, String jql, String cursor,
                                               int maxResults, List<String> requestedFields) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
        body.put("jql", jql);
        body.put("maxResults", maxResults);
        com.fasterxml.jackson.databind.node.ArrayNode fields = body.putArray("fields");
        for (String f : requestedFields) fields.add(f);
        // Atlassian's documented convention is to send nextPageToken explicitly as null on the
        // first page, not omit the field — ObjectNode.put(String, String) writes a JSON null for
        // a null value.
        body.put("nextPageToken", cursor);
        return post(connection, "/rest/api/3", "/search/jql", body.toString());
    }

    private HttpResponse<String> getLegacySearch(Connection connection, String jql, int startAt,
                                                 int maxResults, List<String> requestedFields) throws Exception {
        String path = "/search?jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8)
                + "&fields=" + String.join(",", requestedFields)
                + "&startAt=" + startAt + "&maxResults=" + maxResults;
        return get(connection, path);
    }

    private void maybeReconcile(Connection connection) throws Exception {
        Instant now = Instant.now();
        Instant last = lastReconciledAt.get(connection.id());
        if (last != null && last.plus(reconcileInterval).isAfter(now)) return;

        catalogRepository.beginInventory(connection.id(), "jira");
        String cursor = null;
        try {
            do {
                IssuePage page = searchIssues(connection, "order by key asc", cursor, 100, List.of("updated"));
                for (JsonNode issue : page.issues()) {
                    catalogRepository.recordInventoryId(connection.id(), "jira",
                            issue.path("key").asText(null));
                }
                cursor = page.nextCursor();
            } while (cursor != null);
            purgeMissing(connection);
            lastReconciledAt.put(connection.id(), now);
        } finally {
            catalogRepository.clearInventory(connection.id(), "jira");
        }
    }

    private void purgeMissing(Connection connection) {
        Set<String> stale = catalogRepository.findMissingFromInventory(connection.id(), "jira");
        stale.forEach(key -> {
            catalogRepository.deleteResource(connection.id(), "jira", key);
            ingestionService.purgeSource(connection.id() + ":" + key);
        });
        if (!stale.isEmpty()) {
            log.info("Jira reconciliation purged {} stale issue(s) for connection {}",
                    stale.size(), connection.id());
        }
    }

    private static List<JsonNode> toList(JsonNode arrayNode) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode n : arrayNode) out.add(n);
        return out;
    }

    @Override
    public void handleWebhookPayload(Connection connection, String rawPayload) throws Exception {
        JsonNode body = mapper.readTree(rawPayload);
        String eventType = body.path("webhookEvent").asText("");
        String issueKey = body.path("issue").path("key").asText(null);
        if (issueKey == null) {
            log.info("Jira webhook payload for connection {} had no issue key — ignoring", connection.id());
            return;
        }
        if (eventType.contains("deleted")) {
            catalogRepository.deleteResource(connection.id(), "jira", issueKey);
            ingestionService.purgeSource(connection.id() + ":" + issueKey);
            return;
        }
        HttpResponse<String> res = get(connection, "/issue/" + issueKey
                + "?fields=" + String.join(",", CATALOG_FIELDS));
        if (res.statusCode() == 404) {
            catalogRepository.deleteResource(connection.id(), "jira", issueKey);
            ingestionService.purgeSource(connection.id() + ":" + issueKey);
            return;
        }
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Failed to refetch Jira issue " + issueKey
                    + " after webhook (HTTP " + res.statusCode() + ")");
        }
        catalogIssue(connection, mapper.readTree(res.body()));
    }

    @Override
    public void hydrate(Connection connection, CatalogResource resource) throws Exception {
        HttpResponse<String> res = get(connection, resource.apiPath());
        if (res.statusCode() == 404) {
            catalogRepository.deleteResource(connection.id(), "jira", resource.externalId());
            ingestionService.purgeSource(connection.id() + ":" + resource.externalId());
            return;
        }
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Failed to lazily fetch Jira issue " + resource.externalId()
                    + " (HTTP " + res.statusCode() + "): " + snippet(res.body()));
        }
        ingestIssue(connection, mapper.readTree(res.body()));
    }

    private void catalogIssue(Connection connection, JsonNode issue) {
        String key = issue.path("key").asText(null);
        JsonNode fields = issue.path("fields");
        if (key == null || fields.isMissingNode()) return;
        String title = fields.path("summary").asText(key);
        String projectKey = fields.path("project").path("key").asText(null);
        String projectName = fields.path("project").path("name").asText(projectKey);
        catalogRepository.upsertContainer(connection.id(), "jira", projectKey, projectName,
                projectKey == null ? null : connection.baseUrl() + "/browse/" + projectKey);
        String updated = fields.path("updated").asText(null);
        Instant sourceUpdatedAt = updated == null ? null : Instant.parse(normalizeJiraTimestamp(updated));
        SourceCatalogRepository.UpsertResult result = catalogRepository.upsertResource(
                connection.id(), "jira", key, projectKey, projectName, title,
                "/issue/" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "?fields=" + FIELDS_CSV,
                connection.baseUrl() + "/browse/" + key, sourceUpdatedAt);
        if (result.invalidatedIndexedContent()) {
            ingestionService.purgeSource(connection.id() + ":" + key);
        }
    }

    private void ingestIssue(Connection connection, JsonNode issue) throws Exception {
        String key = issue.path("key").asText(null);
        JsonNode fields = issue.path("fields");
        if (key == null || fields.isMissingNode()) return;

        String summary = fields.path("summary").asText("Untitled");
        String status = fields.path("status").path("name").asText("");
        String assignee = fields.path("assignee").path("displayName").asText("Unassigned");
        String priority = fields.path("priority").path("name").asText("");
        String projectKey = fields.path("project").path("key").asText(null);
        StringBuilder labels = new StringBuilder();
        for (JsonNode l : fields.path("labels")) {
            if (labels.length() > 0) labels.append(", ");
            labels.append(l.asText());
        }
        String description = renderRichText(fields.path("description"));

        StringBuilder md = new StringBuilder();
        md.append("# ").append(summary).append("\n\n");
        md.append("**Status:** ").append(status).append("\n");
        md.append("**Assignee:** ").append(assignee).append("\n");
        md.append("**Priority:** ").append(priority).append("\n");
        md.append("**Labels:** ").append(labels).append("\n\n");
        md.append("## Description\n").append(description).append("\n\n");
        md.append("## Comments\n");
        for (JsonNode comment : loadComments(connection, key, fields.path("comment"))) {
            String author = comment.path("author").path("displayName").asText("Unknown");
            String commentBody = renderRichText(comment.path("body"));
            md.append("- ").append(author).append(": ").append(commentBody).append("\n");
        }

        String updatedStr = fields.path("updated").asText(null);
        Instant sourceUpdatedAt = updatedStr == null ? null : Instant.parse(normalizeJiraTimestamp(updatedStr));

        List<String> aclTags = new ArrayList<>(connection.aclScope());
        aclTags.add("connection:" + connection.id());
        aclTags.add("jira:project:" + (projectKey == null ? "unknown" : projectKey));

        String url = connection.baseUrl() + "/browse/" + key;

        ingestionService.ingest(
                connection.id() + ":" + key, key + " " + summary, projectKey,
                md.toString().getBytes(StandardCharsets.UTF_8), "text/markdown", aclTags,
                "jira", key, url, sourceUpdatedAt
        );
    }

    /**
     * Jira search embeds comments for the common case but may cap that expansion. Only pay for
     * the dedicated paginated endpoint when its reported total proves the inline set is partial.
     */
    private List<JsonNode> loadComments(Connection connection, String issueKey,
                                        JsonNode embeddedPage) throws Exception {
        List<JsonNode> embedded = toList(embeddedPage.path("comments"));
        int reportedTotal = embeddedPage.path("total").asInt(embedded.size());
        if (reportedTotal <= embedded.size()) return embedded;

        List<JsonNode> all = new ArrayList<>(reportedTotal);
        int startAt = 0;
        int total = reportedTotal;
        String apiRoot = connection.deploymentType() == DeploymentType.CLOUD
                ? "/rest/api/3" : "/rest/api/2";
        while (startAt < total) {
            HttpResponse<String> res = get(connection, apiRoot,
                    "/issue/" + URLEncoder.encode(issueKey, StandardCharsets.UTF_8)
                            + "/comment?startAt=" + startAt + "&maxResults=100");
            if (res.statusCode() != 200) {
                throw new IllegalStateException("Failed to fetch all comments for Jira issue "
                        + issueKey + " (HTTP " + res.statusCode() + "): " + snippet(res.body()));
            }
            JsonNode page = mapper.readTree(res.body());
            List<JsonNode> comments = toList(page.path("comments"));
            if (comments.isEmpty()) break;
            all.addAll(comments);
            startAt += comments.size();
            total = page.path("total").asInt(total);
        }
        return all;
    }

    /** Renders both Jira Server/DC strings and Jira Cloud Atlassian Document Format as plain text. */
    private static String renderRichText(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return "";
        if (value.isTextual()) return value.asText();
        StringBuilder out = new StringBuilder();
        appendRichText(value, out);
        return out.toString().strip();
    }

    private static void appendRichText(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) return;
        if (node.isTextual()) {
            out.append(node.asText());
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) appendRichText(child, out);
            return;
        }

        String type = node.path("type").asText("");
        if ("hardBreak".equals(type)) {
            appendNewline(out);
            return;
        }
        if ("listItem".equals(type)) out.append("- ");

        JsonNode text = node.get("text");
        if (text != null && text.isTextual()) {
            out.append(text.asText());
        } else if (!node.has("content")) {
            JsonNode attrs = node.path("attrs");
            String fallback = attrs.path("text").asText(
                    attrs.path("displayName").asText(attrs.path("url").asText("")));
            out.append(fallback);
        }
        appendRichText(node.path("content"), out);

        if ("paragraph".equals(type) || "heading".equals(type) || "blockquote".equals(type)
                || "listItem".equals(type) || "codeBlock".equals(type)) {
            appendNewline(out);
        }
    }

    private static void appendNewline(StringBuilder out) {
        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') out.append('\n');
    }

    /** Jira's "updated" field is e.g. "2024-01-01T12:00:00.000+0000" (offset without a colon) — normalize to ISO-8601. */
    private static String normalizeJiraTimestamp(String jiraTimestamp) {
        String s = jiraTimestamp.trim();
        if (s.length() >= 5 && (s.charAt(s.length() - 5) == '+' || s.charAt(s.length() - 5) == '-')
                && s.charAt(s.length() - 3) != ':') {
            s = s.substring(0, s.length() - 2) + ":" + s.substring(s.length() - 2);
        }
        return Instant.parse(java.time.OffsetDateTime.parse(s).toInstant().toString()).toString();
    }

    private HttpResponse<String> get(Connection connection, String path) throws Exception {
        return get(connection, "/rest/api/2", path);
    }

    private HttpResponse<String> get(Connection connection, String apiRoot, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(connection.baseUrl() + apiRoot + path))
                .header("Authorization", AtlassianAuth.authorizationHeader(connection.authMode(),
                        connection.authUsername(), credentialCipher.decrypt(connection.authSecretEncrypted())))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Connection connection, String apiRoot, String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(connection.baseUrl() + apiRoot + path))
                .header("Authorization", AtlassianAuth.authorizationHeader(connection.authMode(),
                        connection.authUsername(), credentialCipher.decrypt(connection.authSecretEncrypted())))
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
