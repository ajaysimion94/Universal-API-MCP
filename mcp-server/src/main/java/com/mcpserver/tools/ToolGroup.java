package com.mcpserver.tools;

import java.time.Instant;
import java.util.UUID;

/**
 * A user-defined group of whole apps ({@code APP} members reference connections) and/or
 * individual endpoints ({@code TOOL} members reference api_tools rows). The {@code slug} doubles
 * as the {@code @} handle in the search grammar ({@code @group #action}), so it stays stable
 * across renames — renaming regenerating it would break saved queries. Immutable — updates go
 * through the {@code with*} methods and are persisted by re-saving via
 * {@link ToolGroupRepository#save}.
 */
public record ToolGroup(
        String id,
        String slug,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt
) {

    public enum MemberType { APP, TOOL }

    public record ToolGroupMember(String groupId, MemberType memberType, String memberId,
                                  Instant createdAt) {
        public static ToolGroupMember of(String groupId, MemberType memberType, String memberId) {
            return new ToolGroupMember(groupId, memberType, memberId, Instant.now());
        }
    }

    public static ToolGroup create(String slug, String name, String description) {
        Instant now = Instant.now();
        return new ToolGroup(UUID.randomUUID().toString(), slug, name, description, now, now);
    }

    /** Rename keeps the slug stable — it doubles as the {@code @} handle in saved queries. */
    public ToolGroup withRenamed(String newName, String newDescription) {
        return new ToolGroup(id, slug, newName, newDescription, createdAt, Instant.now());
    }
}
