package com.mcpserver.models;

import java.time.Instant;
import java.util.UUID;

public record FileNode(
        String id,
        String parentId,
        String name,
        NodeType type,
        Long size,
        String mimeType,
        String owner,
        String visibility,
        Instant createdAt,
        Instant updatedAt,
        Integer version
) {

    public enum NodeType {
        FOLDER, FILE
    }

    public static FileNode folder(String id, String parentId, String name, String owner, String visibility) {
        Instant now = Instant.now();
        return new FileNode(id, parentId, name, NodeType.FOLDER, 0L, null, owner, visibility, now, now, 1);
    }

    public static FileNode file(String id, String parentId, String name, long size, String mimeType,
                                String owner, String visibility) {
        Instant now = Instant.now();
        return new FileNode(id, parentId, name, NodeType.FILE, size, mimeType, owner, visibility, now, now, 1);
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
