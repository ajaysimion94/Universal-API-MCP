package com.mcpserver;

import com.mcpserver.models.BulkUploadResult;
import com.mcpserver.models.FileNode;
import com.mcpserver.services.FileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FileServiceTests {

    @Autowired
    private FileService fileService;

    @Test
    void rootExistsAndCanBeListed() {
        FileNode root = fileService.getRoot();
        assertThat(root.id()).isEqualTo("root");
        assertThat(root.type()).isEqualTo(FileNode.NodeType.FOLDER);
    }

    @Test
    void canCreateFolderAndListItAsAChild() {
        FileNode root = fileService.getRoot();
        FileNode folder = fileService.createFolder(root.id(), "Runbooks", "ajay", "everyone");

        List<FileNode> children = fileService.listChildren(root.id());
        assertThat(children).extracting(FileNode::name).contains("Runbooks");
        assertThat(folder.parentId()).isEqualTo(root.id());
    }

    @Test
    void canUploadFileAndItAppearsAsAChild() throws Exception {
        FileNode root = fileService.getRoot();
        MockMultipartFile upload = new MockMultipartFile(
                "file", "incident.md", "text/markdown", "# postmortem".getBytes());

        FileNode file = fileService.uploadFile(root.id(), upload, "ajay", "everyone");

        assertThat(file.name()).isEqualTo("incident.md");
        assertThat(file.size()).isGreaterThan(0L);
        List<FileNode> children = fileService.listChildren(root.id());
        assertThat(children).extracting(FileNode::name).contains("incident.md");
    }

    @Test
    void bulkFolderUploadRecreatesHierarchyAndIsIdempotent() throws Exception {
        FileNode root = fileService.getRoot();
        MockMultipartFile a = new MockMultipartFile(
                "files", "a.txt", "text/plain", "a".getBytes());
        MockMultipartFile b = new MockMultipartFile(
                "files", "b.txt", "text/plain", "b".getBytes());
        MockMultipartFile c = new MockMultipartFile(
                "files", "c.md", "text/markdown", "# c".getBytes());

        List<String> paths = List.of(
                "BulkDocs/notes/a.txt",
                "BulkDocs/notes/b.txt",
                "BulkDocs/guides/c.md"
        );
        List<MockMultipartFile> files = List.of(a, b, c);

        BulkUploadResult first = fileService.uploadFolder(root.id(), paths, cast(files), "ajay", "everyone");
        assertThat(first.foldersCreated()).isEqualTo(3); // BulkDocs, notes, guides
        assertThat(first.filesUploaded()).isEqualTo(3);
        assertThat(first.filesSkipped()).isEqualTo(0);

        // Second identical upload: folders reused, files skipped — idempotent.
        BulkUploadResult second = fileService.uploadFolder(root.id(), paths, cast(files), "ajay", "everyone");
        assertThat(second.foldersCreated()).isEqualTo(0);
        assertThat(second.filesUploaded()).isEqualTo(0);
        assertThat(second.filesSkipped()).isEqualTo(3);

        // Verify the tree shape under BulkDocs.
        FileNode bulk = fileService.listChildren(root.id()).stream()
                .filter(n -> n.name().equals("BulkDocs"))
                .findFirst().orElseThrow();
        FileNode notes = fileService.listChildren(bulk.id()).stream()
                .filter(n -> n.name().equals("notes")).findFirst().orElseThrow();
        FileNode guides = fileService.listChildren(bulk.id()).stream()
                .filter(n -> n.name().equals("guides")).findFirst().orElseThrow();
        assertThat(fileService.listChildren(notes.id())).hasSize(2);
        assertThat(fileService.listChildren(guides.id())).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    private static List<org.springframework.web.multipart.MultipartFile> cast(
            List<MockMultipartFile> files) {
        return (List<org.springframework.web.multipart.MultipartFile>) (List<?>) files;
    }
}

