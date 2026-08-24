package com.mcpserver.tools;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.mcpserver.connectors.AuthMode;
import com.mcpserver.connectors.ApiUrlMode;
import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.ConnectionType;
import com.mcpserver.connectors.CredentialCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ApiToolExecutorTests {

    @Autowired
    private ApiToolExecutor executor;
    @Autowired
    private CredentialCipher credentialCipher;

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

    private Connection connection(AuthMode authMode, String username, String secret) {
        return Connection.create(ConnectionType.API_COLLECTION, "Test-" + UUID.randomUUID(),
                "http://localhost:" + wireMock.port(), authMode, username,
                secret == null ? null : credentialCipher.encrypt(secret), List.of());
    }

    private ApiTool tool(String method, String urlTemplate, String schema, String locations,
                         String bodyTemplate) {
        return tool(method, urlTemplate, schema, locations, "{}", bodyTemplate);
    }

    private ApiTool tool(String method, String urlTemplate, String schema, String locations,
                         String headers, String bodyTemplate) {
        return new ApiTool(UUID.randomUUID().toString(), "conn-test", "testapp",
                "testapp_request", "request", "Request", "", "general", method, urlTemplate,
                schema, locations, headers, bodyTemplate, null,
                true, false, false, Instant.now(), Instant.now());
    }

    @Test
    void rendersPathQueryAndBodyFromArgs() throws Exception {
        stubFor(post(urlPathEqualTo("/todos/42/comments")).willReturn(okJson("{\"ok\":true}")));

        ApiTool tool = tool("POST", "/todos/{todoId}/comments",
                """
                {"type":"object","properties":{
                    "todoId":{"type":"integer"},
                    "verbose":{"type":"boolean"},
                    "text":{"type":"string"}},
                 "required":["todoId","text"]}""",
                "{\"todoId\":\"path\",\"verbose\":\"query\",\"text\":\"body\"}",
                "{\"text\":\"\"}");

        ToolInvocationResult result = executor.execute(tool, connection(AuthMode.NONE, null, null),
                Map.of("todoId", "42", "verbose", "true", "text", "hello"));

        assertThat(result.status()).isEqualTo(200);
        verify(postRequestedFor(urlPathEqualTo("/todos/42/comments"))
                .withQueryParam("verbose", equalTo("true"))
                .withRequestBody(equalToJson("{\"text\":\"hello\"}")));
    }

    @Test
    void injectsBearerAuth() throws Exception {
        stubFor(get(urlPathEqualTo("/me")).willReturn(okJson("{}")));

        executor.execute(tool("GET", "/me", "{\"type\":\"object\"}", "{}", null),
                connection(AuthMode.BEARER, null, "tok-123"), Map.of());

        verify(getRequestedFor(urlPathEqualTo("/me"))
                .withHeader("Authorization", equalTo("Bearer tok-123")));
    }

    @Test
    void injectsApiKeyHeaderUsingStoredHeaderName() throws Exception {
        stubFor(get(urlPathEqualTo("/me")).willReturn(okJson("{}")));

        executor.execute(tool("GET", "/me", "{\"type\":\"object\"}", "{}", null),
                connection(AuthMode.API_KEY_HEADER, "X-Api-Key", "key-456"), Map.of());

        verify(getRequestedFor(urlPathEqualTo("/me"))
                .withHeader("X-Api-Key", equalTo("key-456")));
    }

    @Test
    void sourceUrlModeCallsTheExactImportedHostInsteadOfConnectionBase() throws Exception {
        WireMockServer sourceHost = new WireMockServer(0);
        sourceHost.start();
        try {
            sourceHost.stubFor(get(urlPathEqualTo("/v2/items/42"))
                    .willReturn(okJson("{\"id\":42}")));
            ApiTool sourceTool = tool("GET", sourceHost.baseUrl() + "/v2/items/{itemId}",
                    "{\"type\":\"object\",\"properties\":{\"itemId\":{\"type\":\"integer\"}},\"required\":[\"itemId\"]}",
                    "{\"itemId\":\"path\"}", null);
            Connection sourceConnection = connection(AuthMode.NONE, null, null)
                    .withApiUrlMode(ApiUrlMode.SOURCE_URLS);

            ToolInvocationResult result = executor.execute(sourceTool, sourceConnection,
                    Map.of("itemId", "42"));

            assertThat(result.status()).isEqualTo(200);
            sourceHost.verify(getRequestedFor(urlPathEqualTo("/v2/items/42")));
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        } finally {
            sourceHost.stop();
        }
    }

    @Test
    void executesAnOriginPinnedDraftUrlWithDoubleBraceParameters() throws Exception {
        stubFor(get(urlPathEqualTo("/todos/17")).willReturn(okJson("{\"id\":17}")));
        Connection conn = connection(AuthMode.NONE, null, null);
        ApiTool tool = tool("GET", "/todos", "{\"type\":\"object\"}", "{}", null);
        ApiToolExecutor.InvokeOverrides overrides = new ApiToolExecutor.InvokeOverrides(
                Map.of(), Map.of(), null, null, null, null,
                wireMock.baseUrl() + "/todos/{{id}}", "GET");

        ToolInvocationResult result = executor.execute(tool, conn, Map.of("id", "17"), overrides);

        assertThat(result.status()).isEqualTo(200);
        verify(getRequestedFor(urlPathEqualTo("/todos/17")));
    }

    @Test
    void draftUrlCannotLeaveTheConnectionOrigin() {
        Connection conn = connection(AuthMode.NONE, null, null);
        ApiTool tool = tool("GET", "/todos", "{\"type\":\"object\"}", "{}", null);
        ApiToolExecutor.InvokeOverrides overrides = new ApiToolExecutor.InvokeOverrides(
                Map.of(), Map.of(), null, null, null, null, "https://example.test/todos", "GET");

        assertThatThrownBy(() -> executor.renderPreview(tool, conn, Map.of(), overrides))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside its allowlisted origin");
    }

    @Test
    void connectionBaseModeRejectsAStaleAbsoluteSourceTemplate() {
        ApiTool stale = tool("GET", "https://unexpected.example.test/items",
                "{\"type\":\"object\"}", "{}", null);

        assertThatThrownBy(() -> executor.execute(stale,
                connection(AuthMode.NONE, null, null), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("re-import");
    }

    @Test
    void missingRequiredArgFailsValidationWithoutExecuting() {
        ApiTool tool = tool("GET", "/todos/{todoId}",
                "{\"type\":\"object\",\"properties\":{\"todoId\":{\"type\":\"integer\"}},\"required\":[\"todoId\"]}",
                "{\"todoId\":\"path\"}", null);

        assertThatThrownBy(() -> executor.execute(tool, connection(AuthMode.NONE, null, null), Map.of()))
                .isInstanceOf(ToolValidationException.class)
                .satisfies(e -> assertThat(((ToolValidationException) e).violations())
                        .extracting(SchemaValidator.Violation::param).containsExactly("todoId"));
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void typeViolationFailsValidationWithoutExecuting() {
        ApiTool tool = tool("GET", "/todos/{todoId}",
                "{\"type\":\"object\",\"properties\":{\"todoId\":{\"type\":\"integer\"}},\"required\":[\"todoId\"]}",
                "{\"todoId\":\"path\"}", null);

        assertThatThrownBy(() -> executor.execute(tool, connection(AuthMode.NONE, null, null),
                Map.of("todoId", "not-a-number")))
                .isInstanceOf(ToolValidationException.class);
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void disabledToolRefusesExecution() {
        ApiTool disabled = tool("GET", "/me", "{\"type\":\"object\"}", "{}", null)
                .withEnabled(false);

        assertThatThrownBy(() -> executor.execute(disabled, connection(AuthMode.NONE, null, null), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void schemaDefaultsFillMissingOptionalArgs() throws Exception {
        stubFor(get(urlPathEqualTo("/todos")).willReturn(okJson("[]")));

        ApiTool tool = tool("GET", "/todos",
                "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"default\":20}}}",
                "{\"limit\":\"query\"}", null);
        executor.execute(tool, connection(AuthMode.NONE, null, null), Map.of());

        verify(getRequestedFor(urlPathEqualTo("/todos")).withQueryParam("limit", equalTo("20")));
    }

    @Test
    void rendersRawAndUrlEncodedImportedBodies() throws Exception {
        stubFor(post(urlPathEqualTo("/raw")).willReturn(ok()));
        stubFor(post(urlPathEqualTo("/form")).willReturn(ok()));

        executor.execute(tool("POST", "/raw",
                        "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}",
                        "{\"name\":\"body\"}", "{\"Content-Type\":\"application/xml\"}",
                        "<item>{name}</item>"),
                connection(AuthMode.NONE, null, null), Map.of("name", "milk"));

        executor.execute(tool("POST", "/form",
                        "{\"type\":\"object\",\"properties\":{\"user_name\":{\"type\":\"string\"}}}",
                        "{\"user_name\":\"body\"}",
                        "{\"Content-Type\":\"application/x-www-form-urlencoded\"}",
                        "user-name={user_name}"),
                connection(AuthMode.NONE, null, null), Map.of("user_name", "A Jay"));

        verify(postRequestedFor(urlPathEqualTo("/raw"))
                .withHeader("Content-Type", equalTo("application/xml"))
                .withRequestBody(equalTo("<item>milk</item>")));
        verify(postRequestedFor(urlPathEqualTo("/form"))
                .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
                .withRequestBody(equalTo("user-name=A+Jay")));
    }

    @Test
    void largeResponsesAreTruncatedForDisplay() throws Exception {
        stubFor(get(urlPathEqualTo("/big"))
                .willReturn(aResponse().withBody("x".repeat(30_000))));

        ToolInvocationResult result = executor.execute(
                tool("GET", "/big", "{\"type\":\"object\"}", "{}", null),
                connection(AuthMode.NONE, null, null), Map.of());

        assertThat(result.truncated()).isTrue();
        assertThat(result.body()).hasSize(20_000);
    }

    @Test
    void rateLimitKicksInPerTool() throws Exception {
        stubFor(get(urlPathEqualTo("/ping")).willReturn(okJson("{}")));
        ApiTool tool = tool("GET", "/ping", "{\"type\":\"object\"}", "{}", null);
        Connection conn = connection(AuthMode.NONE, null, null);

        for (int i = 0; i < 10; i++) {
            executor.execute(tool, conn, Map.of());
        }
        assertThatThrownBy(() -> executor.execute(tool, conn, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Rate limit");
    }
}
