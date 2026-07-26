package com.mcpserver.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One-shot WebSocket exchange: connect, send options/consents/prompt, stream
 * {@code appendText} events back, finish on the server's {@code done} event.
 * <p>
 * Completion semantics: {@code onDone} fires only after the server explicitly sent
 * {@code done}; a socket that closes (or errors) before that is reported via
 * {@code onError}, so callers never mistake a truncated reply for a complete one.
 */
public class CopilotWebSocket {

    private static final Logger log = LoggerFactory.getLogger(CopilotWebSocket.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final java.net.http.HttpClient httpClient;
    private final String conversationId;
    private final String prompt;
    private final String mode;
    private final Consumer<String> onText;
    private final Runnable onDone;
    private final Consumer<String> onError;
    private final int timeoutSeconds;

    private volatile WebSocket webSocket;
    private volatile boolean doneReceived = false;
    private volatile boolean closeSent = false;
    private final StringBuilder buffer = new StringBuilder();

    /**
     * @param httpClient  the client's shared HTTP client — its cookie handler ties the
     *                    WebSocket handshake to the session established over plain HTTP
     */
    public CopilotWebSocket(java.net.http.HttpClient httpClient,
                            String conversationId, String prompt, String mode,
                            Consumer<String> onText, Runnable onDone,
                            Consumer<String> onError, int timeoutSeconds) {
        this.httpClient = httpClient;
        this.conversationId = conversationId;
        this.prompt = prompt;
        this.mode = mode;
        this.onText = onText;
        this.onDone = onDone;
        this.onError = onError;
        this.timeoutSeconds = timeoutSeconds;
    }

    public void connect(String wsUrl) throws Exception {
        CompletableFuture<WebSocket> future = httpClient
                .newWebSocketBuilder()
                .header("User-Agent", CopilotClient.USER_AGENT)
                .header("Origin", CopilotProtocol.BASE_URL)
                .buildAsync(java.net.URI.create(wsUrl), new Listener());
        try {
            webSocket = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            // Surface the handshake's HTTP status — 401/403 means the server wants a
            // signed-in accessToken on the URL (anonymous chat is gated server-side).
            if (e.getCause() instanceof java.net.http.WebSocketHandshakeException hse) {
                int status = hse.getResponse().statusCode();
                String hint = (status == 401 || status == 403)
                        ? " — the server requires a signed-in session (configure chat.copilot.access-token)"
                        : "";
                throw new RuntimeException("Copilot WebSocket handshake failed (HTTP " + status + ")" + hint, e);
            }
            throw e;
        }

        webSocket.sendText(CopilotProtocol.setOptionsFrame(), true);
        webSocket.sendText(CopilotProtocol.consentsFrame(), true);
        webSocket.sendText(CopilotProtocol.sendFrame(conversationId, prompt, mode), true);
    }

    public void close() {
        WebSocket ws = webSocket;
        if (ws != null && !closeSent) {
            closeSent = true;
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    private class Listener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket ws) {
            log.debug("Copilot WebSocket opened");
            WebSocket.Listener.super.onOpen(ws);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String full = buffer.toString();
                buffer.setLength(0);
                processFrame(full);
            }
            return WebSocket.Listener.super.onText(ws, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            return WebSocket.Listener.super.onBinary(ws, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.debug("Copilot WebSocket closed: {} {}", statusCode, reason);
            if (doneReceived) {
                onDone.run();
            } else {
                onError.accept("Copilot connection closed before the response completed"
                        + " (status " + statusCode + (reason == null || reason.isBlank() ? "" : ", " + reason) + ")");
            }
            return WebSocket.Listener.super.onClose(ws, statusCode, reason);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.error("Copilot WebSocket error", error);
            if (!doneReceived) {
                onError.accept(error.getMessage() != null ? error.getMessage() : error.toString());
            }
            WebSocket.Listener.super.onError(ws, error);
        }
    }

    /** A frame is normally one JSON event, but may carry several newline-delimited events. */
    private void processFrame(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return;
        try {
            handleMessage(mapper.readTree(trimmed));
        } catch (Exception whole) {
            boolean anyParsed = false;
            for (String line : trimmed.split("\\r?\\n")) {
                String l = line.trim();
                if (!l.startsWith("{")) continue;
                try {
                    handleMessage(mapper.readTree(l));
                    anyParsed = true;
                } catch (Exception lineError) {
                    log.warn("Skipping unparseable Copilot event: {}", abbreviate(l), lineError);
                }
            }
            if (!anyParsed) {
                log.warn("Skipping unparseable Copilot frame: {}", abbreviate(trimmed));
            }
        }
    }

    private void handleMessage(JsonNode msg) {
        String event = msg.path("event").asText("");
        try {
            switch (event) {
                case "challenge" -> handleChallenge(msg);
                case "appendText" -> {
                    String text = msg.path("text").asText();
                    if (!text.isEmpty()) onText.accept(text);
                }
                case "done" -> {
                    log.debug("Copilot response complete");
                    doneReceived = true;
                    close();
                }
                case "error" -> {
                    String code = msg.path("errorCode").asText("unknown");
                    onError.accept("Copilot error: " + code);
                }
                default -> log.debug("Ignoring Copilot event '{}'", event);
            }
        } catch (Exception e) {
            log.error("Failed to handle Copilot event '{}'", event, e);
            if ("challenge".equals(event)) {
                onError.accept("Failed to answer Copilot challenge: " + e.getMessage());
            }
        }
    }

    private void handleChallenge(JsonNode msg) throws Exception {
        String method = msg.path("method").asText();
        String parameter = msg.path("parameter").asText();
        String id = msg.path("id").asText("");

        String token = switch (method) {
            case "hashcash" -> CopilotChallenge.solveHashcash(parameter);
            case "copilot" -> CopilotChallenge.solveCopilotChallenge(parameter);
            default -> throw new RuntimeException("Unknown challenge method: " + method);
        };

        webSocket.sendText(CopilotProtocol.challengeResponse(token, method, id), true);
        webSocket.sendText(CopilotProtocol.sendFrame(conversationId, prompt, mode), true);
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…(" + s.length() + " chars)";
    }
}
