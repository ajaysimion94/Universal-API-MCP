package com.mcpserver.connectors;

import com.mcpserver.tools.SpecFetcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionControllerTests {

    @Test
    void explicitConnectionTestQueuesAHealthCheckWithoutCreatingAnotherConnection() {
        ConnectionService service = mock(ConnectionService.class);
        ConnectionController controller = new ConnectionController(service, mock(SpecFetcher.class));
        when(service.startTestConnectionJob("connection-1")).thenReturn("job-17");

        Map<String, String> response = controller.test("connection-1");

        assertThat(response).containsEntry("jobId", "job-17").containsEntry("status", "running");
        verify(service).startTestConnectionJob("connection-1");
    }

    @Test
    void jsonApiCollectionImportPassesBaseUrlOverrideToService() {
        ConnectionService service = mock(ConnectionService.class);
        ConnectionController controller = new ConnectionController(service, mock(SpecFetcher.class));
        ConnectionController.CreateRequest request = new ConnectionController.CreateRequest();
        request.type = "API_COLLECTION";
        request.name = "Orders";
        request.specUrl = "https://docs.example.test/openapi.json";
        request.baseUrl = "https://staging-api.example.test/v1";
        request.authMode = "NONE";
        request.apiUrlMode = "CONNECTION_BASE";
        when(service.createApiCollection(eq("Orders"), eq(AuthMode.NONE), eq(null), eq(null),
                eq(List.of()), eq(request.specUrl), eq(null), eq(request.baseUrl),
                eq(ApiUrlMode.CONNECTION_BASE)))
                .thenReturn(new ConnectionService.CreateResult("connection-1", "job-1"));

        Map<String, Object> response = controller.create(request);

        assertThat(response).containsEntry("id", "connection-1").containsEntry("jobId", "job-1");
        verify(service).createApiCollection("Orders", AuthMode.NONE, null, null, List.of(),
                request.specUrl, null, request.baseUrl, ApiUrlMode.CONNECTION_BASE);
    }

    @Test
    void uploadedSpecImportPassesBaseUrlOverrideToService() throws Exception {
        ConnectionService service = mock(ConnectionService.class);
        ConnectionController controller = new ConnectionController(service, mock(SpecFetcher.class));
        MockMultipartFile spec = new MockMultipartFile("file", "orders.json", "application/json",
                "{\"openapi\":\"3.0.3\"}".getBytes(StandardCharsets.UTF_8));
        when(service.createApiCollection(eq("Orders"), eq(AuthMode.NONE), eq(null), eq(null),
                eq(List.of()), eq(null), eq("{\"openapi\":\"3.0.3\"}"),
                eq("https://staging-api.example.test/v1"), eq(ApiUrlMode.CONNECTION_BASE)))
                .thenReturn(new ConnectionService.CreateResult("connection-1", "job-1"));

        Map<String, Object> response = controller.importSpec(spec, "Orders", "NONE", null, null,
                null, "https://staging-api.example.test/v1", "CONNECTION_BASE");

        assertThat(response).containsEntry("id", "connection-1").containsEntry("jobId", "job-1");
        verify(service).createApiCollection("Orders", AuthMode.NONE, null, null, List.of(), null,
                "{\"openapi\":\"3.0.3\"}", "https://staging-api.example.test/v1",
                ApiUrlMode.CONNECTION_BASE);
    }
}
