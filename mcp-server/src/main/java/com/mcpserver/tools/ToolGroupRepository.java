package com.mcpserver.tools;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ToolGroupRepository {

    private final JdbcTemplate jdbc;

    public ToolGroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ToolGroup> MAPPER = (rs, rowNum) -> new ToolGroup(
            rs.getString("id"),
            rs.getString("slug"),
            rs.getString("name"),
            rs.getString("description"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    private static final RowMapper<ToolGroup.ToolGroupMember> MEMBER_MAPPER = (rs, rowNum) ->
            new ToolGroup.ToolGroupMember(
                    rs.getString("group_id"),
                    ToolGroup.MemberType.valueOf(rs.getString("member_type")),
                    rs.getString("member_id"),
                    Instant.parse(rs.getString("created_at"))
            );

    public void save(ToolGroup g) {
        jdbc.update("""
                INSERT OR REPLACE INTO tool_groups
                    (id, slug, name, description, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                g.id(), g.slug(), g.name(), g.description(),
                g.createdAt().toString(), g.updatedAt().toString()
        );
    }

    public List<ToolGroup> findAll() {
        return jdbc.query("SELECT * FROM tool_groups ORDER BY name", MAPPER);
    }

    public Optional<ToolGroup> findById(String id) {
        List<ToolGroup> rows = jdbc.query("SELECT * FROM tool_groups WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<ToolGroup> findBySlug(String slug) {
        List<ToolGroup> rows = jdbc.query("SELECT * FROM tool_groups WHERE slug = ?", MAPPER, slug);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Slug dedupe on create (two "Dev" groups must not collide on the same @ handle). */
    public boolean slugExists(String slug) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tool_groups WHERE slug = ?", Integer.class, slug);
        return count != null && count > 0;
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM tool_groups WHERE id = ?", id);
    }

    public List<ToolGroup.ToolGroupMember> findMembers(String groupId) {
        return jdbc.query(
                "SELECT * FROM tool_group_members WHERE group_id = ? ORDER BY member_type, created_at",
                MEMBER_MAPPER, groupId);
    }

    /** Bulk replace — the service validates every member id before this runs. */
    public void replaceMembers(String groupId, List<ToolGroup.ToolGroupMember> members) {
        deleteMembersForGroup(groupId);
        for (ToolGroup.ToolGroupMember m : members) {
            jdbc.update("""
                    INSERT OR REPLACE INTO tool_group_members (group_id, member_type, member_id, created_at)
                    VALUES (?, ?, ?, ?)
                    """, groupId, m.memberType().name(), m.memberId(), m.createdAt().toString());
        }
    }

    public void deleteMembersForGroup(String groupId) {
        jdbc.update("DELETE FROM tool_group_members WHERE group_id = ?", groupId);
    }

    /** Cascade cleanup for connection deletion (no DB FKs, consistent with existing style). */
    public void deleteMembersForConnection(String connectionId) {
        jdbc.update("DELETE FROM tool_group_members WHERE member_type = 'APP' AND member_id = ?",
                connectionId);
    }

    /** Cascade cleanup for tool deletion (re-import drop, connection deletion). */
    public void deleteMembersForTool(String toolId) {
        jdbc.update("DELETE FROM tool_group_members WHERE member_type = 'TOOL' AND member_id = ?",
                toolId);
    }
}
