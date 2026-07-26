package com.mcpserver.services;

import com.mcpserver.copilot.CopilotClient;
import com.mcpserver.plugins.CopilotChatPlugin;
import com.mcpserver.plugins.PluginRegistry;
import com.mcpserver.rag.retrieval.SearchPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Answer generation for the Web UI Chat page (see DECISIONS.md 2026-07-25 — chat answers
 * via external Copilot). Each turn grounds the user's message with RAG retrieval
 * ({@link SearchService}), builds a cited-context prompt ({@link ChatPromptBuilder}), and
 * streams the generated answer back over the Copilot WebSocket client.
 * <p>
 * Retrieval failures never block the chat — the turn degrades to an ungrounded answer.
 * Generation failures are reported to the caller after one silent retry (only when no
 * answer text had been emitted yet, so a retry can never duplicate streamed text).
 * <p>
 * Scope note: this path exists for the Web UI only; the MCP context path stays
 * retrieval-only (no answer text generated) per the standing product principle.
 */
@Service
public class CopilotChatService {

    private static final Logger log = LoggerFactory.getLogger(CopilotChatService.class);

    private final SearchService searchService;
    private final CopilotClient copilotClient;
    private final int contextTopK;
    private final int maxContextChars;

    private final PluginRegistry pluginRegistry;

    public CopilotChatService(SearchService searchService,
                              @Value("${chat.copilot.timeout-seconds:180}") int timeoutSeconds,
                              @Value("${chat.copilot.connect-timeout-seconds:30}") int connectTimeoutSeconds,
                              @Value("${chat.copilot.mode:smart}") String mode,
                              @Value("${chat.copilot.context-top-k:6}") int contextTopK,
                              @Value("${chat.copilot.max-context-chars:1500}") int maxContextChars,
                              @Value("${chat.copilot.access-token:}") String accessToken,
                              @Value("${chat.copilot.identity-type:}") String identityType,
                              @Value("${chat.copilot.cookies:}") String cookies,
                              PluginRegistry pluginRegistry) {
        this.searchService = searchService;
        this.copilotClient = new CopilotClient(Duration.ofSeconds(connectTimeoutSeconds), timeoutSeconds);
        this.copilotClient.setMode(mode);
        // Optional signed-in credentials (anonymous chat is gated server-side by Copilot):
        // an accessToken copied from the browser's WebSocket URL, and/or the raw Cookie
        // header from any authenticated copilot.microsoft.com request.
        if (!accessToken.isBlank()) this.copilotClient.setAccessToken(accessToken);
        if (!identityType.isBlank()) this.copilotClient.setIdentityType(identityType);
        if (!cookies.isBlank()) this.copilotClient.setSessionCookies(parseCookieString(cookies));
        this.contextTopK = contextTopK;
        this.maxContextChars = maxContextChars;
        this.pluginRegistry = pluginRegistry;
    }

    /** Parses a raw Cookie header value ({@code "k1=v1; k2=v2"}) into a cookie map. */
    private static Map<String, String> parseCookieString(String raw) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String pair : raw.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return out;
    }

    /** Callbacks for one chat turn; invoked in order: sources → chunk* → done|error. */
    public interface ChatHandler {
        void onSources(List<SearchPipeline.SearchResult> sources);
        void onChunk(String text);
        void onDone(String conversationId);
        void onError(String message);
    }

    /**
     * Runs one chat turn end to end. Blocking — call from a worker thread.
     *
     * @param conversationId  a Copilot conversation id from an earlier turn, or null to start one
     */
    public void streamChat(String message, String conversationId, boolean includeWeb, ChatHandler handler) {
        List<SearchPipeline.SearchResult> sources = retrieve(message, includeWeb);
        handler.onSources(sources);

        if (!pluginRegistry.isReady(CopilotChatPlugin.ID)) {
            handler.onError("Generated answers are unavailable — enable the Copilot Chat (legacy) plugin "
                    + "and configure its environment credentials, or use the retrieved sources below.");
            return;
        }

        String prompt = ChatPromptBuilder.build(message, sources, maxContextChars);
        AtomicBoolean anyChunk = new AtomicBoolean(false);
        try {
            streamOnce(prompt, conversationId, handler, anyChunk);
        } catch (Exception first) {
            if (anyChunk.get()) {
                // Partial answer already streamed — retrying would duplicate text. Report failure.
                log.warn("Copilot chat failed mid-stream: {}", first.getMessage());
                handler.onError(friendlyMessage(first));
                return;
            }
            log.info("Copilot chat failed before any output ({}); retrying once with a fresh conversation",
                    first.getMessage());
            try {
                streamOnce(prompt, null, handler, anyChunk);
            } catch (Exception second) {
                log.warn("Copilot chat retry failed: {}", second.getMessage());
                handler.onError(friendlyMessage(second));
            }
        }
    }

    private void streamOnce(String prompt, String conversationId, ChatHandler handler,
                            AtomicBoolean anyChunk) throws Exception {
        CopilotClient.CopilotChatResult result = copilotClient.stream(prompt, conversationId, chunk -> {
            anyChunk.set(true);
            handler.onChunk(chunk);
        });
        handler.onDone(result.conversationId());
    }

    /** Retrieval best-effort: plugin/DB problems degrade to an ungrounded answer, never to a failed chat. */
    private List<SearchPipeline.SearchResult> retrieve(String message, boolean includeWeb) {
        try {
            return searchService.search(message, contextTopK, List.of(), includeWeb);
        } catch (Exception e) {
            log.warn("Grounding retrieval failed (continuing ungrounded): {}", e.getMessage());
            return List.of();
        }
    }

    private static String friendlyMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
        return "Answer generation failed — " + msg;
    }
}
