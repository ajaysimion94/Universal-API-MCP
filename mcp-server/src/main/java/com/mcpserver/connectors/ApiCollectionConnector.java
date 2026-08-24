package com.mcpserver.connectors;

import com.mcpserver.config.TlsHttpClientFactory;
import com.mcpserver.services.IngestionService;
import com.mcpserver.tools.ApiTool;
import com.mcpserver.tools.ApiToolDefinition;
import com.mcpserver.tools.ApiToolExecutor;
import com.mcpserver.tools.ApiToolService;
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
import java.util.ArrayList;

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
    private final HttpClient httpClient;

    public ApiCollectionConnector(SpecFetcher specFetcher,
                                  ApiToolService apiToolService,
                                  ApiToolExecutor apiToolExecutor,
                                  ConnectionRepository connectionRepository,
                                  IngestionService ingestionService,
                                  TlsHttpClientFactory tlsHttpClientFactory) {
        this.specFetcher = specFetcher;
        this.apiToolService = apiToolService;
        this.apiToolExecutor = apiToolExecutor;
        this.connectionRepository = connectionRepository;
        this.ingestionService = ingestionService;
        this.httpClient = tlsHttpClientFactory.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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

        // A supplied connection override takes precedence in connection-base mode. Source-URL
        // mode deliberately ignores it, retaining the document's operation hosts instead.
        String documentBaseUrl = SpecFetcher.resolveBaseUrl(spec);
        String effectiveBaseUrl = current.apiUrlMode() == ApiUrlMode.CONNECTION_BASE
                && current.baseUrlOverride() != null
                ? current.baseUrlOverride()
                : documentBaseUrl;
        if (effectiveBaseUrl == null && current.apiUrlMode() != ApiUrlMode.SOURCE_URLS) {
            throw new IllegalArgumentException("Couldn't determine the API URL from the document — "
                    + "declare a base URL/server URL in the Postman/OpenAPI document or set a Base URL override");
        }
        current = current.withBaseUrl(effectiveBaseUrl == null ? "" : effectiveBaseUrl);
        connectionRepository.save(current);

        boolean preserveSourceUrls = current.apiUrlMode() == ApiUrlMode.SOURCE_URLS;
        List<ApiToolDefinition> definitions = spec.parser().parse(spec.parsed(), preserveSourceUrls);
        if (preserveSourceUrls) {
            definitions = resolveSourceUrls(definitions, current, spec);
        }
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("The spec parsed but contains no requests/operations");
        }
        int imported = apiToolService.importTools(current, definitions);
        log.info("Connection {}: imported {} tools from {} spec", current.id(), imported,
                spec.parser().format());

        if (!preserveSourceUrls) probeBaseUrl(current);
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
                tool.urlTemplate().matches("^https?://.*")
                        ? tool.urlTemplate()
                        : connection.baseUrl().replaceAll("/+$", "") + tool.urlTemplate(),
                Instant.now()
        );
    }

    /**
     * SOURCE_URLS mode persists a concrete absolute URL per imported tool. Absolute Postman
     * request URLs and OpenAPI server URLs pass through unchanged; relative URLs resolve against
     * the fetched spec URL (OpenAPI) or the collection URL detected from the document.
     */
    private List<ApiToolDefinition> resolveSourceUrls(List<ApiToolDefinition> definitions,
                                                      Connection connection,
                                                      SpecFetcher.FetchedSpec spec) {
        List<ApiToolDefinition> resolved = new ArrayList<>(definitions.size());
        for (ApiToolDefinition def : definitions) {
            String url = def.urlTemplate();
            if (!url.matches("^https?://.*")) {
                URI base = sourceUrlBase(connection, spec);
                if (base == null) {
                    throw new IllegalArgumentException("Request '" + def.displayName()
                            + "' has a relative URL, but the document declares no API URL");
                }
                url = resolveUrlTemplate(base, url);
            }
            URI parsed = URI.create(url.replaceAll("\\{[^}]+}", "x"));
            if (parsed.getHost() == null || parsed.getUserInfo() != null
                    || !("http".equalsIgnoreCase(parsed.getScheme())
                    || "https".equalsIgnoreCase(parsed.getScheme()))) {
                throw new IllegalArgumentException("Request '" + def.displayName()
                        + "' does not contain a safe HTTP(S) source URL");
            }
            resolved.add(new ApiToolDefinition(def.displayName(), def.requestSlug(),
                    def.description(), def.category(), def.httpMethod(), url,
                    def.paramsSchema(), def.paramLocations(), def.staticHeaders(),
                    def.bodyTemplate(), def.primaryParam()));
        }
        return resolved;
    }

    private String resolveUrlTemplate(URI base, String template) {
        // URI rejects RFC 6570-style braces, so protect them while resolving a relative URL and
        // restore the executable tool template afterward.
        String safeTemplate = template.replace("{", "%7B").replace("}", "%7D");
        return base.resolve(safeTemplate).toString()
                .replace("%7B", "{").replace("%7D", "}")
                .replace("%7b", "{").replace("%7d", "}");
    }

    private URI sourceUrlBase(Connection connection, SpecFetcher.FetchedSpec spec) {
        if ("OPENAPI".equals(spec.parser().format())
                && spec.resolvedUrl() != null && !spec.resolvedUrl().isBlank()) {
            return URI.create(spec.resolvedUrl());
        }
        if (connection.baseUrl() != null && !connection.baseUrl().isBlank()) {
            String base = connection.baseUrl().endsWith("/")
                    ? connection.baseUrl() : connection.baseUrl() + "/";
            return URI.create(base);
        }
        return null;
    }

    private void probeBaseUrl(Connection connection) {
        if (connection.baseUrl() == null || connection.baseUrl().isBlank()) return;
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
                    connection.baseUrl(), failureDetail(e));
            log.debug("Base URL probe stack trace for {}", connection.baseUrl(), e);
        }
    }

    static String failureDetail(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + error.getMessage();
    }
}
