package com.mcpserver.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.ConnectionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REST surface for custom tool groups: CRUD, bulk membership replacement (whole apps and/or
 * individual endpoints), and batch enable/disable. Thin adapter — all logic lives in
 * {@link ToolGroupService}.
 */
@RestController
@RequestMapping("/api/groups")
public class ToolGroupController {

    private final ToolGroupService toolGroupService;
    private final ConnectionService connectionService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolGroupController(ToolGroupService toolGroupService,
                               ConnectionService connectionService) {
        this.toolGroupService = toolGroupService;
        this.connectionService = connectionService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return toolGroupService.findAll().stream().map(this::summaryMap).toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody GroupRequest req) {
        return summaryMap(toolGroupService.create(req.name, req.description));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return detailMap(toolGroupService.findById(id));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody GroupRequest req) {
        return summaryMap(toolGroupService.update(id, req.name, req.description));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        toolGroupService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Bulk membership replace: {members: [{memberType: "APP"|"TOOL", memberId}]}. */
    @PutMapping("/{id}/members")
    public Map<String, Object> setMembers(@PathVariable String id, @RequestBody MembersRequest req) {
        List<ToolGroup.ToolGroupMember> members = req.members == null ? List.of()
                : req.members.stream()
                        .map(m -> ToolGroup.ToolGroupMember.of(id, parseMemberType(m.memberType), m.memberId))
                        .toList();
        toolGroupService.setMembers(id, members);
        return detailMap(toolGroupService.findById(id));
    }

    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(@PathVariable String id) {
        return Map.of("updated", toolGroupService.setGroupEnabled(id, true));
    }

    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable String id) {
        return Map.of("updated", toolGroupService.setGroupEnabled(id, false));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    private static ToolGroup.MemberType parseMemberType(String raw) {
        try {
            return ToolGroup.MemberType.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("memberType must be APP or TOOL, got: " + raw);
        }
    }

    /** List shape: group fields plus resolved counts for the sidebar/directory. */
    private Map<String, Object> summaryMap(ToolGroup g) {
        List<ApiTool> tools = toolGroupService.toolsInGroup(g.id());
        long appCount = toolGroupService.findMembers(g.id()).stream()
                .filter(m -> m.memberType() == ToolGroup.MemberType.APP)
                .count();
        Map<String, Object> map = groupMap(g);
        map.put("appCount", appCount);
        map.put("toolCount", tools.size());
        map.put("enabledToolCount", tools.stream().filter(ApiTool::enabled).count());
        return map;
    }

    /** Detail shape: summary plus resolved members — apps as connection summaries, tools in the same shape ApiToolController uses. */
    private Map<String, Object> detailMap(ToolGroup g) {
        Map<String, Object> map = summaryMap(g);
        List<Map<String, Object>> apps = new ArrayList<>();
        for (ToolGroup.ToolGroupMember m : toolGroupService.findMembers(g.id())) {
            if (m.memberType() == ToolGroup.MemberType.APP) {
                try {
                    apps.add(connectionSummary(connectionService.findById(m.memberId())));
                } catch (IllegalArgumentException e) {
                    // stale membership — cascade cleanup normally removes it
                }
            }
        }
        map.put("apps", apps);
        map.put("tools", toolGroupService.toolsInGroup(g.id()).stream().map(this::toolMap).toList());
        return map;
    }

    private Map<String, Object> groupMap(ToolGroup g) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", g.id());
        map.put("slug", g.slug());
        map.put("name", g.name());
        map.put("description", g.description() == null ? "" : g.description());
        map.put("createdAt", g.createdAt().toString());
        map.put("updatedAt", g.updatedAt().toString());
        return map;
    }

    private Map<String, Object> connectionSummary(Connection c) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", c.id());
        map.put("type", c.type().name());
        map.put("name", c.name());
        map.put("baseUrl", c.baseUrl());
        map.put("status", c.status().name());
        return map;
    }

    private Map<String, Object> toolMap(ApiTool t) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", t.id());
        map.put("connectionId", t.connectionId());
        map.put("appSlug", t.appSlug());
        map.put("name", t.name());
        map.put("requestSlug", t.requestSlug());
        map.put("displayName", t.displayName());
        map.put("description", t.description() == null ? "" : t.description());
        map.put("category", t.category());
        map.put("method", t.httpMethod());
        map.put("urlTemplate", t.urlTemplate());
        map.put("enabled", t.enabled());
        map.put("pending", t.pending());
        map.put("knowledgeSource", t.knowledgeSource());
        map.put("primaryParam", t.primaryParam());
        try {
            map.put("paramsSchema", mapper.readTree(t.paramsSchema()));
        } catch (Exception e) {
            map.put("paramsSchema", Map.of("type", "object"));
        }
        return map;
    }

    public static class GroupRequest {
        public String name;
        public String description;
    }

    public static class MembersRequest {
        public List<MemberInput> members;
    }

    public static class MemberInput {
        public String memberType;
        public String memberId;
    }
}
