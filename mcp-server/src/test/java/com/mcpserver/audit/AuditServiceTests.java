package com.mcpserver.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class AuditServiceTests {

    @Autowired
    private AuditService auditService;

    @Test
    void testAuditLoggingAndQuerying() {
        String actor = "test-actor-" + System.currentTimeMillis();
        auditService.logToolInvoked("tool-1", "test_tool", "wf-123", actor, Map.of("p", "v"));
        auditService.logToolExecuted("tool-1", "test_tool", "wf-123", actor, "Executed ok");

        // Query with actor filter
        Map<String, Object> result = auditService.query(actor, null, null, null, null, 0, 10);
        assertThat(result).containsKey("items");
        assertThat((long) result.get("total")).isEqualTo(2);

        // Query with event type filter
        Map<String, Object> resultInvoked = auditService.query(actor, null, AuditEventType.TOOL_INVOKED.name(), null, null, 0, 10);
        assertThat((long) resultInvoked.get("total")).isEqualTo(1);
    }
}
