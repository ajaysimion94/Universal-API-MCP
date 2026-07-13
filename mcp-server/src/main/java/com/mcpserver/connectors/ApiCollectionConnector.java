package com.mcpserver.connectors;

import com.mcpserver.services.IngestionService;
import com.mcpserver.tools.ApiTool;
import com.mcpserver.tools.ApiToolDefinition;
import com.mcpserver.tools.ApiToolExecutor;
import com.mcpserver.tools.ApiToolService;
import com.mcpserver.tools.OpenApiParser;
import com.mcpserver.tools.SpecFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Connector for imported API definitions (Postman collection / OpenAPI spec — §8 zero-code
 * onboarding). Unlike Confluence/Jira this source has no content to crawl per se: "testing the
 * connection" means fetching + parsing the spec and (re-)importing its tools, and
 * backfill/pollDelta refresh the GET tools flagged as knowledge sources by invoking them and
 * ingesting their responses. Webhooks and deployment detection don't apply — documented no-ops.
 */
@Component
public class ApiCollectionConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(ApiCollectionConnector.class);

    private final SpecFetcher specFetcher;
    private final ApiToolService apiToolService;
    private final ApiToolExecutor apiToolExecutor;
    private final ConnectionRepository connectionRepository;
    private final IngestionService ingestionService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ApiCollectionConnector(SpecFetcher specFetcher,
                                  ApiToolService apiToolService,
                                  ApiToolExecutor apiToolExecutor,
                                  ConnectionRepository connectionRepository,
                                  IngestionService ingestionService) {
        this.specFetcher = specFetcher;
        this.apiToolService = apiToolService;
        this.apiToolExecutor = apiToolExecutor;
        this.connectionRepository = connectionRepository;
        this.ingestionService = ingestionService;
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.API_COLLECTION;
    }

    /** Cloud vs Server/DC has no meaning for an imported API definition. */
    @Override
    public DeploymentType detectDeployment(Connection connection) {
        return DeploymentType.UNKNOWN;
    }

    /**
     * Parse-and-import IS the connection test: fetch the spec (URL) or reread the stored document
     * (upload), parse, upsert tools. A parse failure fails the connection with the parser's
     * message. The base URL is probed best-effort afterward — an unreachable root is logged, not
     * fatal (plenty of APIs 404 at "/").
     */
    @Override
    public void testConnection(Connection connection) throws Exception {
        SpecFetcher.FetchedSpec spec;
        if (connection.specSourceUrl() != null && !connection.specSourceUrl().isBlank()) {
            spec = specFetcher.fetch(connection.specSourceUrl());
        } else if (connection.specDocument() != null && !connection.specDocument().isBlank()) {
            spec = specFetcher.parseContent(connection.specDocument(), null);
        } else {
            throw new IllegalArgumentException(
                    "API connection needs a spec URL or an uploaded spec file");
        }

        Connection current = connection.withSpec(
                spec.resolvedUrl() != null ? spec.resolvedUrl() : connection.specSourceUrl(),
                spec.parser().format(), spec.content());

        // No base URL supplied → derive from the spec (OpenAPI servers[0].url)
        if (current.baseUrl() == null || current.baseUrl().isBlank()) {
            String serverUrl = OpenApiParser.extractServerUrl(spec.parsed());
            if (serverUrl != null && spec.resolvedUrl() != null && !serverUrl.matches("^https?://.*")) {
                serverUrl = URI.create(spec.resolvedUrl()).resolve(serverUrl).toString();
            }
            if (serverUrl == null || !serverUrl.matches("^https?://.*")) {
                throw new IllegalArgumentException("Couldn't determine the API base URL from the "
                        + "spec — edit the connection and set it explicitly");
            }
            current = new Connection(current.id(), current.type(), current.name(), serverUrl,
                    current.deploymentType(), current.authMode(), current.authUsername(),
                    current.authSecretEncrypted(), current.status(), current.lastError(),
                    current.syncCursor(), current.webhookRegistered(), current.aclScope(),
                    current.createdAt(), Instant.now(), current.lastSyncedAt(),
                    current.specSourceUrl(), current.specFormat(), current.specDocument());
        }
        connectionRepository.save(current);

        List<ApiToolDefinition> definitions = spec.parser().parse(spec.parsed());
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("The spec parsed but contains no requests/operations");
        }
        int imported = apiToolService.importTools(current, definitions);
        log.info("Connection {}: imported {} tools from {} spec", current.id(), imported,
                spec.parser().format());

        probeBaseUrl(current);
    }

    /** Manual "refresh knowledge now" — same work as pollDelta, with progress for the job UI. */
    @Override
    public void backfill(Connection connection, BackfillProgressSink sink) throws Exception {
        List<ApiTool> sources = apiToolService.findKnowledgeSources(connection.id());
        int done = 0;
        sink.progress(0, sources.size());
        for (ApiTool tool : sources) {
            ingestKnowledgeSource(connection, tool);
            sink.progress(++done, sources.size());
        }
    }

    /** APIs don't push spec changes — no webhook to register. */
    @Override
    public void registerWebhook(Connection connection) {
        // no-op: webhookRegistered stays false, connection is poll-only by design
    }

    /** Scheduled knowledge-source refresh: re-invoke flagged GET tools and re-ingest. */
    @Override
    public void pollDelta(Connection connection) throws Exception {
        for (ApiTool tool : apiToolService.findKnowledgeSources(connection.id())) {
            try {
                ingestKnowledgeSource(connection, tool);
            } catch (Exception e) {
                log.warn("Knowledge-source refresh failed for tool {}: {}", tool.name(), e.getMessage());
            }
        }
    }

    @Override
    public void handleWebhookPayload(Connection connection, String rawPayload) {
        throw new UnsupportedOperationException("API_COLLECTION connections do not accept webhooks");
    }

    private void ingestKnowledgeSource(Connection connection, ApiTool tool) throws Exception {
        byte[] response = apiToolExecutor.executeForIngestion(tool, connection);
        ingestionService.ingest(
                connection.id() + ":" + tool.id(),
                tool.displayName(),
                tool.appSlug() + "/" + tool.category(),
                response,
                "application/json",
                List.of("connection:" + connection.id(), "api:" + tool.appSlug()),
                "api",
                tool.id(),
                connection.baseUrl().replaceAll("/+$", "") + tool.urlTemplate(),
                Instant.now()
        );
    }

    private void probeBaseUrl(Connection connection) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(connection.baseUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            log.info("Base URL probe for {} → HTTP {}", connection.baseUrl(), response.statusCode());
        } catch (Exception e) {
            log.info("Base URL probe for {} failed ({}) — not fatal, tools may still work",
                    connection.baseUrl(), e.getMessage());
        }
    }
}
