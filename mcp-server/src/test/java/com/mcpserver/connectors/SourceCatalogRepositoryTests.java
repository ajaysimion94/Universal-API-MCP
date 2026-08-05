package com.mcpserver.connectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SourceCatalogRepositoryTests {

    @Autowired
    private SourceCatalogRepository repository;

    @MockBean
    private EventQueueWorker eventQueueWorker;

    @Test
    void indexesContainerTitleAndContentLocationsWithoutStoringTheBody() {
        String connectionId = "catalog-" + UUID.randomUUID();
        repository.upsertContainer(connectionId, "confluence", "ENG", "Engineering",
                "https://docs.example.test/spaces/ENG");
        SourceCatalogRepository.UpsertResult saved = repository.upsertResource(
                connectionId, "confluence", "42", "ENG", "Engineering",
                "Incident Response Runbook", "/pages/42?body-format=storage",
                "https://docs.example.test/spaces/ENG/pages/42",
                Instant.parse("2026-08-01T00:00:00Z"));

        List<CatalogResource> matches = repository.findTitleCandidates(
                "open the Incident Response Runbook", 10);

        assertThat(matches).extracting(CatalogResource::id).contains(saved.resource().id());
        CatalogResource resource = repository.find(connectionId, "confluence", "42").orElseThrow();
        assertThat(resource.containerName()).isEqualTo("Engineering");
        assertThat(resource.apiPath()).isEqualTo("/pages/42?body-format=storage");
        assertThat(resource.webUrl()).endsWith("/spaces/ENG/pages/42");
        assertThat(resource.contentState()).isEqualTo(CatalogContentState.METADATA_ONLY);
    }

    @Test
    void changedMetadataMarksPreviouslyIndexedContentStale() {
        String connectionId = "catalog-" + UUID.randomUUID();
        SourceCatalogRepository.UpsertResult original = repository.upsertResource(
                connectionId, "jira", "ENG-7", "ENG", "Engineering", "Login failure",
                "/issue/ENG-7", "https://jira.example.test/browse/ENG-7",
                Instant.parse("2026-08-01T00:00:00Z"));
        repository.markIndexed(original.resource().id());

        SourceCatalogRepository.UpsertResult changed = repository.upsertResource(
                connectionId, "jira", "ENG-7", "ENG", "Engineering", "Login failure",
                "/issue/ENG-7", "https://jira.example.test/browse/ENG-7",
                Instant.parse("2026-08-02T00:00:00Z"));

        assertThat(changed.invalidatedIndexedContent()).isTrue();
        assertThat(changed.resource().contentState()).isEqualTo(CatalogContentState.METADATA_ONLY);
        assertThat(changed.resource().contentIndexedAt()).isNull();
    }

    @Test
    void streamingInventoryFindsDeletesWithoutHoldingAllRemoteIdsInMemory() {
        String connectionId = "catalog-" + UUID.randomUUID();
        repository.upsertResource(connectionId, "confluence", "kept", "ENG", "Engineering",
                "Kept page", "/pages/kept", null, null);
        repository.upsertResource(connectionId, "confluence", "deleted", "ENG", "Engineering",
                "Deleted page", "/pages/deleted", null, null);

        repository.beginInventory(connectionId, "confluence");
        repository.recordInventoryId(connectionId, "confluence", "kept");

        assertThat(repository.findMissingFromInventory(connectionId, "confluence"))
                .containsExactly("deleted");
        repository.clearInventory(connectionId, "confluence");
    }
}
