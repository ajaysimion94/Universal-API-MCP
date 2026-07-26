package com.mcpserver.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Client for the Copilot chat API: establishes a session, creates conversations, and
 * exchanges one prompt per WebSocket connection (see {@link CopilotWebSocket}).
 * Anonymous sessions (cookies only) and token-authenticated sessions are both supported.
 * Instances are thread-safe for concurrent chats; session/auth setters are configuration
 * and must be called before first use.
 */
public class CopilotClient {

    private static final Logger log = LoggerFactory.getLogger(CopilotClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * The API edge rejects requests carrying the default {@code Java-http-client/*} agent
     * (observed: 401/403 on conversation creation), so every request presents as a browser.
     */
    static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final HttpClient httpClient;
    private final CookieManager cookieManager;
    private final int timeoutSeconds;

    // volatile: mutated at runtime by the credentials endpoint, read by chat-stream threads
    private volatile Map<String, String> sessionCookies;
    private volatile String accessToken;
    private volatile String identityType;
    private volatile String mode = CopilotProtocol.DEFAULT_MODE;

    public CopilotClient() {
        this(Duration.ofSeconds(30), 300);
    }

    public CopilotClient(Duration connectTimeout, int timeoutSeconds) {
        this.cookieManager = new CookieManager();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .cookieHandler(cookieManager)
                .build();
        this.timeoutSeconds = timeoutSeconds;
    }

    public void setSessionCookies(Map<String, String> cookies) {
        this.sessionCookies = cookies;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public void setIdentityType(String identityType) {
        this.identityType = identityType;
    }

    /**
     * The {@code X-UserIdentityType} to send with both HTTP calls and the WS URL: the
     * explicitly configured value wins; otherwise it is derived from the access token's
     * {@code sub} claim (the real web client computes it exactly this way — google/apple).
     * The server 401s authenticated API calls that lack this header.
     */
    String identityType() {
        if (identityType != null && !identityType.isBlank()) return identityType;
        return deriveIdentityType(accessToken);
    }

    /** Reads the JWT payload's {@code sub} and maps its prefix, mirroring the web client. */
    static String deriveIdentityType(String jwt) {
        if (jwt == null || jwt.isBlank()) return null;
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
            String sub = mapper.readTree(payload).path("sub").asText("");
            if (sub.startsWith("google")) return "google";
            if (sub.startsWith("apple")) return "apple";
        } catch (Exception ignored) {
            // malformed token — no derivation
        }
        return null;
    }

    /** Whether any signed-in credential (token or cookies) is currently configured. */
    public boolean hasCredentials() {
        return (accessToken != null && !accessToken.isBlank())
                || (sessionCookies != null && !sessionCookies.isEmpty());
    }

    /** Drops all configured credentials, back to the anonymous session. */
    public void clearCredentials() {
        this.accessToken = null;
        this.identityType = null;
        this.sessionCookies = null;
    }

    /**
     * Validates the current configuration against the real gate: warms the session, creates
     * a conversation, and opens the chat WebSocket (the point where anonymous access is
     * rejected). Sends a trivial throwaway prompt and closes immediately on success.
     *
     * @throws RuntimeException with the HTTP-status-annotated message on failure
     */
    public void probe() {
        synchronized (cookieManager) {
            try {
                cookieManager.getCookieStore().removeAll();
                ensureSession();
                String conversationId = createConversation();
                CopilotWebSocket ws = new CopilotWebSocket(
                        httpClient, conversationId, "ping", mode,
                        chunk -> {}, () -> {}, err -> {}, 30);
                try {
                    ws.connect(buildWebSocketUrl(conversationId));
                } finally {
                    ws.close();
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
            }
        }
    }

    /** Conversation mode sent in each {@code send} frame (e.g. {@code smart}). */
    public void setMode(String mode) {
        if (mode != null && !mode.isBlank()) this.mode = mode;
    }

    public CopilotChatResult chat(String prompt) throws Exception {
        return chat(prompt, null);
    }

    public CopilotChatResult chat(String prompt, String conversationId) throws Exception {
        return run(prompt, conversationId, null);
    }

    public CopilotChatResult stream(String prompt, Consumer<String> onChunk) throws Exception {
        return stream(prompt, null, onChunk);
    }

    public CopilotChatResult stream(String prompt, String conversationId,
                                    Consumer<String> onChunk) throws Exception {
        return run(prompt, conversationId, onChunk);
    }

    private CopilotChatResult run(String prompt, String conversationId,
                                  Consumer<String> onChunk) throws Exception {
        // Anti-abuse behavior observed on the API: a cookie session that already created a
        // conversation gets 401/403 on the next one. New conversations therefore always start
        // from a pristine cookie session; resumed conversations keep the session they have.
        // The reset → warm-up → create sequence is serialized so concurrent chat turns on
        // this shared client cannot reset each other's session mid-flight.
        String resolvedId = conversationId;
        if (conversationId == null || conversationId.isBlank()) {
            synchronized (cookieManager) {
                cookieManager.getCookieStore().removeAll();
                ensureSession();
                resolvedId = createConversation();
            }
        } else {
            ensureSession();
        }

        String finalConversationId = resolvedId;
        StringBuilder textBuffer = new StringBuilder();
        CompletableFuture<CopilotChatResult> resultFuture = new CompletableFuture<>();

        CopilotWebSocket ws = new CopilotWebSocket(
                httpClient,
                resolvedId, prompt, mode,
                chunk -> {
                    textBuffer.append(chunk);
                    if (onChunk != null) onChunk.accept(chunk);
                },
                () -> resultFuture.complete(new CopilotChatResult(textBuffer.toString(), finalConversationId)),
                err -> {
                    if (!resultFuture.isDone()) {
                        resultFuture.completeExceptionally(new RuntimeException(err));
                    }
                },
                timeoutSeconds
        );

        try {
            ws.connect(buildWebSocketUrl(resolvedId));
            return resultFuture.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            resultFuture.cancel(true);
            throw new RuntimeException("Copilot response timed out after " + timeoutSeconds + "s", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        } finally {
            ws.close();
        }
    }

    /**
     * Warms the session (cookies/token) against the base URL. Auth problems are detected
     * here rather than surfacing later as an opaque WebSocket handshake failure.
     */
    private void ensureSession() throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(CopilotProtocol.BASE_URL + "/"))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(30));

        if (sessionCookies != null && !sessionCookies.isEmpty()) {
            builder.header("Cookie", formatCookies(sessionCookies));
        }
        if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken);
            String idType = identityType();
            if (idType != null) builder.header("X-UserIdentityType", idType);
        }

        HttpResponse<String> response = httpClient.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new RuntimeException("Copilot rejected the session (HTTP " + status
                    + ") — the configured credentials are missing, expired, or blocked");
        }
        if (status >= 400) {
            throw new RuntimeException("Copilot session check failed (HTTP " + status + ")");
        }
    }

    private String createConversation() throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(CopilotProtocol.CONVERSATIONS_URL))
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Origin", CopilotProtocol.BASE_URL)
                .timeout(Duration.ofSeconds(30));

        if (sessionCookies != null && !sessionCookies.isEmpty()) {
            builder.header("Cookie", formatCookies(sessionCookies));
        }
        if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken);
            String idType = identityType();
            if (idType != null) builder.header("X-UserIdentityType", idType);
        }

        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to create conversation: HTTP " + response.statusCode()
                    + " " + response.body());
        }

        JsonNode body = mapper.readTree(response.body());
        String id = body.path("id").asText();
        if (id == null || id.isBlank()) {
            throw new RuntimeException("No conversation id in response: " + response.body());
        }

        log.debug("Created Copilot conversation: {}", id);
        return id;
    }

    private String buildWebSocketUrl(String conversationId) {
        String wsUrl = CopilotProtocol.CHAT_WEBSOCKET_URL
                + "&clientSessionId=" + UUID.randomUUID();
        if (accessToken != null && !accessToken.isBlank()) {
            wsUrl += "&accessToken=" + java.net.URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
            String idType = identityType();
            if (idType != null) {
                wsUrl += "&X-UserIdentityType=" + java.net.URLEncoder.encode(idType, StandardCharsets.UTF_8);
            }
        }
        return wsUrl;
    }

    private String formatCookies(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    public static class CopilotChatResult {
        private final String text;
        private final String conversationId;

        public CopilotChatResult(String text, String conversationId) {
            this.text = text;
            this.conversationId = conversationId;
        }

        public String text() { return text; }
        public String conversationId() { return conversationId; }
    }
}
