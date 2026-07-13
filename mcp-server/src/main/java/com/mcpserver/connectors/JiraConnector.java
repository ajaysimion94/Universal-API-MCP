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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private static final String FIELDS_CSV = String.join(",", FIELDS_LIST);
    private static final DateTimeFormatter JQL_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
            .withZone(ZoneOffset.UTC);

    private final IngestionService ingestionService;
    private final ConnectionRepository connectionRepository;
    private final CredentialCipher credentialCipher;
    private final String webhookBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Per-connection cache of which search endpoint generation works, populated on first use. */
    private final Map<String, Boolean> modernSearchSupported = new ConcurrentHashMap<>();

    public JiraConnector(IngestionService ingestionService,
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
        String cursor = null;
        int processed = 0;
        do {
            IssuePage page = searchIssues(connection, "order by updated asc", cursor, PAGE_SIZE);
            for (JsonNode issue : page.issues()) {
                ingestIssue(connection, issue);
                processed++;
                sink.progress(processed, 0); // neither endpoint gives a reliable upfront total
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        connectionRepository.save(connectionRepository.findById(connection.id())
                .orElse(connection).withSyncCursor(Instant.now().toString()));
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
        String callbackUrl = webhookBaseUrl + "/api/connections/" + connection.id() + "/webhook";
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
            return;
        }
        String cursorJql = JQL_DATE.format(Instant.parse(connection.syncCursor()));
        String jql = "updated >= \"" + cursorJql + "\" order by updated asc";
        String latestSeen = connection.syncCursor();
        String pageCursor = null;
        do {
            IssuePage page = searchIssues(connection, jql, pageCursor, PAGE_SIZE);
            for (JsonNode issue : page.issues()) {
                ingestIssue(connection, issue);
                String updated = issue.path("fields").path("updated").asText(null);
                if (updated != null) latestSeen = normalizeJiraTimestamp(updated);
            }
            pageCursor = page.nextCursor();
        } while (pageCursor != null);
        if (!latestSeen.equals(connection.syncCursor())) {
            connectionRepository.save(connection.withSyncCursor(latestSeen));
        }
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
        Boolean modernOk = modernSearchSupported.get(connection.id());
        boolean firstProbe = modernOk == null;
        if (modernOk == null || modernOk) {
            HttpResponse<String> res = postSearchJql(connection, jql, cursor, maxResults);
            if (res.statusCode() == 200) {
                modernSearchSupported.put(connection.id(), true);
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
                modernSearchSupported.put(connection.id(), false);
            } else {
                throw new IllegalStateException("Jira search failed (HTTP " + res.statusCode() + "): " + snippet(res.body()));
            }
        }
        int startAt = cursor == null ? 0 : Integer.parseInt(cursor);
        HttpResponse<String> res = getLegacySearch(connection, jql, startAt, maxResults);
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

    private HttpResponse<String> postSearchJql(Connection connection, String jql, String cursor, int maxResults) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
        body.put("jql", jql);
        body.put("maxResults", maxResults);
        com.fasterxml.jackson.databind.node.ArrayNode fields = body.putArray("fields");
        for (String f : FIELDS_LIST) fields.add(f);
        // Atlassian's documented convention is to send nextPageToken explicitly as null on the
        // first page, not omit the field — ObjectNode.put(String, String) writes a JSON null for
        // a null value.
        body.put("nextPageToken", cursor);
        return post(connection, "/rest/api/3", "/search/jql", body.toString());
    }

    private HttpResponse<String> getLegacySearch(Connection connection, String jql, int startAt, int maxResults) throws Exception {
        String path = "/search?jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8)
                + "&fields=" + FIELDS_CSV + "&startAt=" + startAt + "&maxResults=" + maxResults;
        return get(connection, path);
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
            ingestionService.purgeSource(connection.id() + ":" + issueKey);
            return;
        }
        HttpResponse<String> res = get(connection, "/issue/" + issueKey + "?fields=" + FIELDS_CSV);
        if (res.statusCode() == 404) {
            ingestionService.purgeSource(connection.id() + ":" + issueKey);
            return;
        }
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Failed to refetch Jira issue " + issueKey
                    + " after webhook (HTTP " + res.statusCode() + ")");
        }
        ingestIssue(connection, mapper.readTree(res.body()));
    }

    private void ingestIssue(Connection connection, JsonNode issue) {
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
        String description = fields.path("description").asText("");

        StringBuilder md = new StringBuilder();
        md.append("# ").append(summary).append("\n\n");
        md.append("**Status:** ").append(status).append("\n");
        md.append("**Assignee:** ").append(assignee).append("\n");
        md.append("**Priority:** ").append(priority).append("\n");
        md.append("**Labels:** ").append(labels).append("\n\n");
        md.append("## Description\n").append(description).append("\n\n");
        md.append("## Comments\n");
        for (JsonNode comment : fields.path("comment").path("comments")) {
            String author = comment.path("author").path("displayName").asText("Unknown");
            String commentBody = comment.path("body").asText("");
            md.append("- ").append(author).append(": ").append(commentBody).append("\n");
        }

        String updatedStr = fields.path("updated").asText(null);
        Instant sourceUpdatedAt = updatedStr == null ? null : Instant.parse(normalizeJiraTimestamp(updatedStr));

        List<String> aclTags = List.of(
                "connection:" + connection.id(),
                "jira:project:" + (projectKey == null ? "unknown" : projectKey));

        String url = connection.baseUrl() + "/browse/" + key;

        ingestionService.ingest(
                connection.id() + ":" + key, key + " " + summary, projectKey,
                md.toString().getBytes(StandardCharsets.UTF_8), "text/markdown", aclTags,
                "jira", key, url, sourceUpdatedAt
        );
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
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(connection.baseUrl() + "/rest/api/2" + path))
                .header("Authorization", AtlassianAuth.basicAuthHeader(connection.authUsername(),
                        credentialCipher.decrypt(connection.authSecretEncrypted())))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Connection connection, String apiRoot, String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(connection.baseUrl() + apiRoot + path))
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
