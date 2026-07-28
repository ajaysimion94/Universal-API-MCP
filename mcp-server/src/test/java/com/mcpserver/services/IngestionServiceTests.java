package com.mcpserver.services;

import com.mcpserver.cache.CacheService;
import com.mcpserver.plugins.PluginRegistry;
import com.mcpserver.rag.chunking.Chunker;
import com.mcpserver.rag.embedding.EmbeddingClient;
import com.mcpserver.repositories.ChunkRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionServiceTests {

    private final Chunker chunker = mock(Chunker.class);
    private final ChunkRepository repository = mock(ChunkRepository.class);
    private final CacheService cache = new CacheService(60, 30);
    private final IngestionService service = new IngestionService(
            chunker, mock(EmbeddingClient.class), repository, mock(PluginRegistry.class),
            new IngestionProgressTracker(), cache, 500, 50);

    @AfterEach
    void stopWorker() {
        service.shutdown();
    }

    @Test
    void successfulIngestionInvalidatesCachedSearchResults() {
        cache.putSearchResult("stale", List.of("old"));
        when(chunker.chunk(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(new Chunker.ChunkText("new content", 0, 3)));

        service.ingest("file-1", "guide.txt", "/guide.txt",
                "new content".getBytes(StandardCharsets.UTF_8), "text/plain", List.of("public"));

        assertThat(cache.getSearchResult("stale")).isEmpty();
    }

    @Test
    void blankReplacementRemovesOldChunksAndInvalidatesSearchCache() {
        cache.putSearchResult("stale", List.of("old"));

        service.ingest("file-1", "guide.txt", "/guide.txt",
                new byte[0], "text/plain", List.of("public"));

        verify(repository).deleteBySourceFileId("file-1");
        assertThat(cache.getSearchResult("stale")).isEmpty();
    }
}
