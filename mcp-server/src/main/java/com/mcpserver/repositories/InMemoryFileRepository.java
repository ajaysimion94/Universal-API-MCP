package com.mcpserver.repositories;

import com.mcpserver.models.FileNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryFileRepository {

    private final Map<String, FileNode> nodes = new ConcurrentHashMap<>();

    public InMemoryFileRepository() {
        String rootId = "root";
        nodes.put(rootId, FileNode.folder(rootId, null, "My files", "system", "everyone"));
    }

    public Optional<FileNode> findById(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public List<FileNode> findAll() {
        return nodes.values().stream()
                .sorted((a, b) -> {
                    if ("root".equals(a.id())) return -1;
                    if ("root".equals(b.id())) return 1;
                    int typeCompare = a.type().compareTo(b.type());
                    return typeCompare != 0 ? typeCompare : a.name().compareToIgnoreCase(b.name());
                })
                .toList();
    }

    public List<FileNode> findChildren(String parentId) {
        List<FileNode> children = new ArrayList<>();
        for (FileNode node : nodes.values()) {
            if (parentId.equals(node.parentId())) {
                children.add(node);
            }
        }
        children.sort((a, b) -> {
            int typeCompare = a.type().compareTo(b.type());
            if (typeCompare != 0) return typeCompare;
            return a.name().compareToIgnoreCase(b.name());
        });
        return children;
    }

    public List<FileNode> findPath(String id) {
        List<FileNode> path = new ArrayList<>();
        Optional<FileNode> current = findById(id);
        while (current.isPresent() && current.get().parentId() != null) {
            path.add(0, current.get());
            current = findById(current.get().parentId());
        }
        if (current.isPresent()) {
            path.add(0, current.get());
        }
        return path;
    }

    public FileNode save(FileNode node) {
        nodes.put(node.id(), node);
        return node;
    }

    public void deleteById(String id) {
        for (FileNode child : findChildren(id)) {
            deleteById(child.id());
        }
        nodes.remove(id);
    }

    public boolean existsByParentAndName(String parentId, String name, FileNode.NodeType type) {
        return nodes.values().stream()
                .anyMatch(n -> parentId.equals(n.parentId())
                        && name.equalsIgnoreCase(n.name())
                        && n.type() == type);
    }

    public Optional<FileNode> findFolderByParentAndName(String parentId, String name) {
        return nodes.values().stream()
                .filter(n -> parentId.equals(n.parentId())
                        && name.equalsIgnoreCase(n.name())
                        && n.type() == FileNode.NodeType.FOLDER)
                .findFirst();
    }
}
