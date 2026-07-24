package com.mcpserver.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditRepository auditRepository, ObjectMapper objectMapper) {
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
    }

    public void logToolInvoked(String toolId, String toolName, String workflowId, String actor, Map<String, Object> args) {
        String argsJson = null;
        if (args != null) {
            try {
                argsJson = objectMapper.writeValueAsString(args);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize arguments for tool invocation", e);
            }
        }
        
        AuditEvent event = new AuditEvent(
                null, AuditEventType.TOOL_INVOKED, toolId, toolName, workflowId, actor, argsJson, null, null, null, null, Instant.now()
        );
        auditRepository.log(event);
    }

    public void logToolApproved(String toolId, String toolName, String workflowId, String actor) {
        AuditEvent event = new AuditEvent(
                null, AuditEventType.TOOL_APPROVED, toolId, toolName, workflowId, actor, null, null, null, null, null, Instant.now()
        );
        auditRepository.log(event);
    }

    public void logToolRejected(String toolId, String toolName, String workflowId, String actor) {
        AuditEvent event = new AuditEvent(
                null, AuditEventType.TOOL_REJECTED, toolId, toolName, workflowId, actor, null, null, null, null, null, Instant.now()
        );
        auditRepository.log(event);
    }

    public void logToolExecuted(String toolId, String toolName, String workflowId, String actor, String resultSummary) {
        AuditEvent event = new AuditEvent(
                null, AuditEventType.TOOL_EXECUTED, toolId, toolName, workflowId, actor, null, resultSummary, null, null, null, Instant.now()
        );
        auditRepository.log(event);
    }

    public void logToolFailed(String toolId, String toolName, String workflowId, String actor, String error) {
        AuditEvent event = new AuditEvent(
                null, AuditEventType.TOOL_FAILED, toolId, toolName, workflowId, actor, null, null, error, null, null, Instant.now()
        );
        auditRepository.log(event);
    }

    public void logToolExpired(String toolId, String toolName, String workflowId) {
        AuditEvent event = new AuditEvent(
                null, AuditEventType.TOOL_EXPIRED, toolId, toolName, workflowId, "SYSTEM", null, null, null, null, null, Instant.now()
        );
        auditRepository.log(event);
    }

    public void logSearch(String actor, String query) {
        String argsJson = null;
        try {
            argsJson = objectMapper.writeValueAsString(Map.of("query", query));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize arguments for search", e);
        }
        
        AuditEvent event = new AuditEvent(
                null, AuditEventType.SEARCH_PERFORMED, null, "search", null, actor, argsJson, null, null, null, null, Instant.now()
        );
        auditRepository.log(event);
    }

    public Map<String, Object> query(String actor, String toolName, String eventTypeStr, String fromStr, String toStr, int page, int size) {
        AuditEventType eventType = null;
        if (eventTypeStr != null && !eventTypeStr.isBlank()) {
            try {
                eventType = AuditEventType.valueOf(eventTypeStr);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid event type filter: {}", eventTypeStr);
            }
        }

        Instant from = null;
        if (fromStr != null && !fromStr.isBlank()) {
            try {
                from = Instant.parse(fromStr);
            } catch (DateTimeParseException e) {
                log.warn("Invalid from date filter: {}", fromStr);
            }
        }

        Instant to = null;
        if (toStr != null && !toStr.isBlank()) {
            try {
                to = Instant.parse(toStr);
            } catch (DateTimeParseException e) {
                log.warn("Invalid to date filter: {}", toStr);
            }
        }

        List<AuditEvent> events = auditRepository.query(actor, toolName, eventType, from, to, page, size);
        long total = auditRepository.count(actor, toolName, eventType, from, to);

        List<Map<String, Object>> items = events.stream().map(e -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", e.id());
            map.put("eventType", e.eventType().name());
            map.put("toolId", e.toolId());
            map.put("toolName", e.toolName());
            map.put("workflowId", e.workflowId());
            map.put("actor", e.actor());
            
            if (e.arguments() != null) {
                try {
                    map.put("arguments", objectMapper.readValue(e.arguments(), new TypeReference<Map<String, Object>>() {}));
                } catch (JsonProcessingException ex) {
                    map.put("arguments", e.arguments()); // fallback to string if parsing fails
                }
            } else {
                map.put("arguments", null);
            }
            
            map.put("resultSummary", e.resultSummary());
            map.put("error", e.error());
            map.put("createdAt", e.createdAt() != null ? e.createdAt().toString() : null);
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("items", items);
        response.put("total", total);
        response.put("page", page);
        response.put("size", size);

        return response;
    }
}
