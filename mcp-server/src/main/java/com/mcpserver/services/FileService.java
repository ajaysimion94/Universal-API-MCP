package com.mcpserver.services;

import com.mcpserver.models.BulkUploadResult;
import com.mcpserver.models.FileNode;
import com.mcpserver.repositories.InMemoryFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final InMemoryFileRepository repository;
    private final IngestionService ingestionService;

    public FileService(InMemoryFileRepository repository, IngestionService ingestionService) {
        this.repository = repository;
        this.ingestionService = ingestionService;
    }

    public FileNode getRoot() {
        return repository.findById("root").orElseThrow();
    }

    public List<FileNode> listChildren(String parentId) {
        return repository.findChildren(parentId);
    }

    public List<FileNode> getPath(String id) {
        return repository.findPath(id);
    }

    public FileNode createFolder(String parentId, String name, String owner, String visibility) {
        repository.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("Parent folder not found: " + parentId));
        if (repository.existsByParentAndName(parentId, name, FileNode.NodeType.FOLDER)) {
            throw new IllegalStateException("A folder with that name already exists here");
        }
        return repository.save(FileNode.folder(FileNode.newId(), parentId, name, owner, visibility));
    }

    public FileNode uploadFile(String parentId, MultipartFile upload, String owner, String visibility)
            throws IOException {
        repository.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("Parent folder not found: " + parentId));
        String name = upload.getOriginalFilename();
        if (repository.existsByParentAndName(parentId, name, FileNode.NodeType.FILE)) {
            throw new IllegalStateException("A file with that name already exists here");
        }
        FileNode node = repository.save(FileNode.file(
                FileNode.newId(),
                parentId,
                name,
                upload.getSize(),
                upload.getContentType(),
                owner,
                visibility
        ));
        ingest(node, upload.getBytes(), visibility);
        return node;
    }

    /**
     * Bulk upload that recreates a folder hierarchy from each file's relative path.
     * Existing folders are reused; existing files at the same path are skipped (idempotent re-upload).
     * Relative paths use {@code /} as the separator; the last segment is the file name.
     */
    public BulkUploadResult uploadFolder(String rootParentId,
                                         List<String> relativePaths,
                                         List<MultipartFile> files,
                                         String owner,
                                         String visibility) throws IOException {
        repository.findById(rootParentId)
                .orElseThrow(() -> new IllegalArgumentException("Parent folder not found: " + rootParentId));
        if (relativePaths.size() != files.size()) {
            throw new IllegalArgumentException("paths and files length mismatch");
        }

        int foldersCreated = 0;
        int filesUploaded = 0;
        int filesSkipped = 0;

        for (int i = 0; i < files.size(); i++) {
            String relative = relativePaths.get(i);
            MultipartFile file = files.get(i);
            String[] segments = relative.split("[/\\\\]+");
            String currentParentId = rootParentId;

            // Walk folder segments (everything except the last = file name).
            for (int s = 0; s < segments.length - 1; s++) {
                String segment = segments[s];
                if (segment.isBlank()) continue;
                FileNode existing = repository.findFolderByParentAndName(currentParentId, segment)
                        .orElse(null);
                if (existing != null) {
                    currentParentId = existing.id();
                } else {
                    FileNode created = repository.save(
                            FileNode.folder(FileNode.newId(), currentParentId, segment, owner, visibility));
                    currentParentId = created.id();
                    foldersCreated++;
                }
            }

            String fileName = segments[segments.length - 1];
            if (repository.existsByParentAndName(currentParentId, fileName, FileNode.NodeType.FILE)) {
                filesSkipped++;
            } else {
                FileNode node = repository.save(FileNode.file(
                        FileNode.newId(),
                        currentParentId,
                        fileName,
                        file.getSize(),
                        file.getContentType(),
                        owner,
                        visibility
                ));
                filesUploaded++;
                String path = buildSourcePath(currentParentId) + " / " + fileName;
                ingest(node, file.getBytes(), visibility, path);
            }
        }

        return new BulkUploadResult(foldersCreated, filesUploaded, filesSkipped, files.size());
    }

    public void delete(String id) {
        if ("root".equals(id)) {
            throw new IllegalArgumentException("The root folder cannot be deleted");
        }
        FileNode node = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + id));
        repository.deleteById(id);
        if (node.type() == FileNode.NodeType.FILE) {
            ingestionService.purgeSource(node.id());
        }
    }

    private void ingest(FileNode fileNode, byte[] bytes, String visibility) {
        String path = buildSourcePath(fileNode.parentId()) + " / " + fileNode.name();
        ingest(fileNode, bytes, visibility, path);
    }

    private void ingest(FileNode fileNode, byte[] bytes, String visibility, String sourcePath) {
        try {
            List<String> aclTags = List.of("vis:" + visibility, "owner:" + fileNode.owner());
            ingestionService.ingest(fileNode.id(), fileNode.name(), sourcePath,
                    bytes, fileNode.mimeType(), aclTags);
        } catch (Exception e) {
            log.warn("Ingestion failed for {} (search will not cover this file): {}",
                    fileNode.name(), e.getMessage());
        }
    }

    private String buildSourcePath(String folderId) {
        return repository.findPath(folderId).stream()
                .map(FileNode::name)
                .collect(Collectors.joining(" / "));
    }
}
