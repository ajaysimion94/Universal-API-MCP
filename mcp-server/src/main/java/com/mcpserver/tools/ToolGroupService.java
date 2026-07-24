package com.mcpserver.tools;

import com.mcpserver.connectors.ConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Business logic for custom tool groups: CRUD with slug dedupe, validated bulk membership
 * replacement, app/tool expansion with dedupe, batch enable/disable (MCP tool registration
 * follows via the {@code ToolsChangedEvent}s that {@link ApiToolService#setEnabled} publishes),
 * and the {@code @group #keyword} grammar resolution.
 */
@Service
public class ToolGroupService {

    private static final Logger log = LoggerFactory.getLogger(ToolGroupService.class);

    private final ToolGroupRepository repository;
    private final ConnectionService connectionService;
    private final ApiToolService apiToolService;

    public ToolGroupService(ToolGroupRepository repository,
                            ConnectionService connectionService,
                            ApiToolService apiToolService) {
        this.repository = repository;
        this.connectionService = connectionService;
        this.apiToolService = apiToolService;
    }

    public List<ToolGroup> findAll() {
        return repository.findAll();
    }

    public ToolGroup findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + id));
    }

    public Optional<ToolGroup> findBySlug(String slug) {
        return repository.findBySlug(slug);
    }

    /** Slugs dedupe with {@code _2}/{@code _3} suffixes, like ApiToolService.dedupeSlugs. */
    public ToolGroup create(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Group name is required");
        }
        String base = Slugifier.slug(name);
        String slug = base;
        int n = 2;
        while (repository.slugExists(slug)) {
            slug = base + "_" + n;
            n++;
        }
        ToolGroup group = ToolGroup.create(slug, name.trim(), description);
        repository.save(group);
        return group;
    }

    /** Rename keeps the slug stable — it is the {@code @} handle saved queries reference. */
    public ToolGroup update(String id, String name, String description) {
        ToolGroup existing = findById(id);
        ToolGroup updated = existing.withRenamed(
                name != null && !name.isBlank() ? name.trim() : existing.name(),
                description != null ? description : existing.description());
        repository.save(updated);
        return updated;
    }

    public void delete(String id) {
        findById(id); // 400s if missing
        repository.deleteMembersForGroup(id);
        repository.delete(id);
    }

    public List<ToolGroup.ToolGroupMember> findMembers(String groupId) {
        return repository.findMembers(groupId);
    }

    /**
     * Bulk-replaces the group's membership. Every member id is validated first — APP members
     * reference connections, TOOL members reference api tools — so a bad id 400s the whole
     * request without touching the existing membership.
     */
    public void setMembers(String groupId, List<ToolGroup.ToolGroupMember> members) {
        ToolGroup group = findById(groupId);
        for (ToolGroup.ToolGroupMember m : members) {
            if (m.memberType() == ToolGroup.MemberType.APP) {
                connectionService.findById(m.memberId()); // throws IllegalArgumentException on miss
            } else {
                apiToolService.findById(m.memberId()); // throws IllegalArgumentException on miss
            }
        }
        repository.replaceMembers(group.id(), members.stream()
                .map(m -> ToolGroup.ToolGroupMember.of(group.id(), m.memberType(), m.memberId()))
                .toList());
    }

    /**
     * Every tool the group grants access to: APP members expand to their connection's tools,
     * TOOL members add individual endpoints, deduped by tool id.
     */
    public List<ApiTool> toolsInGroup(String groupId) {
        Map<String, ApiTool> byId = new LinkedHashMap<>();
        for (ToolGroup.ToolGroupMember m : repository.findMembers(groupId)) {
            if (m.memberType() == ToolGroup.MemberType.APP) {
                for (ApiTool tool : apiToolService.findByConnectionId(m.memberId())) {
                    byId.putIfAbsent(tool.id(), tool);
                }
            } else {
                try {
                    ApiTool tool = apiToolService.findById(m.memberId());
                    byId.putIfAbsent(tool.id(), tool);
                } catch (IllegalArgumentException e) {
                    // cascade cleanup normally removes these; never let one row break the group
                    log.warn("Skipping stale TOOL membership {} in group {}", m.memberId(), groupId);
                }
            }
        }
        return List.copyOf(byId.values());
    }

    /** Batch enable/disable of every tool in the group; returns how many tools were affected. */
    public int setGroupEnabled(String groupId, boolean enabled) {
        findById(groupId); // 400s if missing
        List<ApiTool> tools = toolsInGroup(groupId);
        for (ApiTool tool : tools) {
            apiToolService.setEnabled(tool.id(), enabled);
        }
        return tools.size();
    }

    /**
     * The {@code @group #keyword} grammar, mirroring {@link ApiToolService#resolveKeyword}
     * scoped to the group's tools: empty keyword → every tool in the group; else exact
     * full-name match → exact request-slug match → prefix matches (an ambiguous prefix returns
     * all candidates for the suggestion list).
     */
    public List<ApiTool> resolveInGroup(String groupSlug, String keyword) {
        ToolGroup group = repository.findBySlug(groupSlug)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupSlug));
        String kw = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT);
        List<ApiTool> pool = toolsInGroup(group.id());
        if (kw.isEmpty()) return pool;

        List<ApiTool> exact = pool.stream()
                .filter(t -> t.name().equals(kw) || t.requestSlug().equals(kw))
                .toList();
        if (!exact.isEmpty()) return exact;

        return pool.stream()
                .filter(t -> t.name().startsWith(kw) || t.requestSlug().startsWith(kw))
                .toList();
    }
}
