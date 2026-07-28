package com.mcpserver.tools;

import com.mcpserver.cache.CacheService;
import com.mcpserver.connectors.CredentialCipher;
import com.mcpserver.workflow.ParameterExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiToolServiceTests {

    @Test
    void changingAToolInvalidatesItsCachedResponses() {
        ApiToolRepository repository = mock(ApiToolRepository.class);
        CacheService cache = mock(CacheService.class);
        ApiTool tool = tool();
        when(repository.findById(tool.id())).thenReturn(Optional.of(tool));
        ApiToolService service = new ApiToolService(
                repository, mock(ToolGroupRepository.class),
                mock(ApplicationEventPublisher.class), mock(ParameterExtractor.class),
                mock(CredentialCipher.class), cache);

        service.setEnabled(tool.id(), false);

        verify(repository).save(argThat(saved ->
                saved.id().equals(tool.id()) && !saved.enabled()));
        verify(cache).invalidateToolResponses(tool.id());
    }

    private static ApiTool tool() {
        Instant now = Instant.now();
        return new ApiTool(
                "tool-1", "connection-1", "example", "example_list", "list", "List",
                "", "", "GET", "https://example.test/items",
                "{\"type\":\"object\"}", "{}", "{}", null, null,
                true, false, false, now, now);
    }
}
