package com.mcpserver.controllers;

import com.mcpserver.services.CopilotChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Web UI Chat answer path: {@code POST /api/chat} streams one assistant turn as Server-Sent
 * Events. Event sequence: {@code sources} (the RAG grounding, same JSON shape as
 * {@code /api/search} results) → {@code chunk}* (answer text deltas) → {@code done}
 * (conversation id for follow-up turns) or {@code error}. Generation failures still carry
 * the {@code sources} event first, so the UI can degrade to showing raw excerpts.
 * <p>
 * The {@code #}/{@code @} tool grammar is NOT handled here — that stays on
 * {@code /api/search} ({@link SearchController}); this endpoint is plain chat messages only.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final CopilotChatService chatService;
    private final ExecutorService executor;

    public ChatController(CopilotChatService chatService) {
        this.chatService = chatService;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "chat-stream");
            t.setDaemon(true);
            return t;
        });
    }

    public record ChatRequest(String message, String conversationId, Boolean web) {}

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? null : request.conversationId();
        boolean web = Boolean.TRUE.equals(request.web());

        // No emitter timeout: the Copilot client enforces its own overall timeout per turn.
        SseEmitter emitter = new SseEmitter(0L);
        executor.submit(() -> {
            try {
                chatService.streamChat(request.message(), conversationId, web, new CopilotChatService.ChatHandler() {
                    @Override
                    public void onSources(List<com.mcpserver.rag.retrieval.SearchPipeline.SearchResult> sources) {
                        send(emitter, "sources", sources.stream().map(SearchResultMapper::toJson).toList());
                    }

                    @Override
                    public void onChunk(String text) {
                        send(emitter, "chunk", Map.of("text", text));
                    }

                    @Override
                    public void onDone(String conversationId) {
                        send(emitter, "done", Map.of("conversationId", conversationId == null ? "" : conversationId));
                    }

                    @Override
                    public void onError(String message) {
                        send(emitter, "error", Map.of("message", message));
                    }
                });
            } catch (StreamClosedException e) {
                log.debug("Chat stream closed by the client mid-turn");
            } catch (Exception e) {
                log.warn("Chat turn failed: {}", e.getMessage());
                try {
                    send(emitter, "error", Map.of("message", "Chat failed — " + e.getMessage()));
                } catch (StreamClosedException ignored) {
                    // client already gone
                }
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // Client disconnected (abort/navigate away) — nothing useful left to do on this turn.
            log.debug("Could not send '{}' event (client likely disconnected): {}", event, e.getMessage());
            throw new StreamClosedException(e);
        }
    }

    /** Marker so a closed stream unwinds the turn quietly instead of logging a stack trace. */
    private static final class StreamClosedException extends RuntimeException {
        StreamClosedException(Throwable cause) { super(cause); }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(409).body(Map.of("error", ex.getMessage()));
    }
}
