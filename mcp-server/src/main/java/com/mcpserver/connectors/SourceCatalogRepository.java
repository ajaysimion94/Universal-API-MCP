package com.mcpserver.connectors;

import com.mcpserver.rag.retrieval.TextSignals;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Durable metadata-only index for large connector sources. */
@Repository
public class SourceCatalogRepository {

    public record UpsertResult(CatalogResource resource, boolean invalidatedIndexedContent) {}

    private final JdbcTemplate jdbc;

    public SourceCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CatalogResource> RESOURCE_MAPPER = (rs, rowNum) ->
            new CatalogResource(
                    rs.getString("id"),
                    rs.getString("connection_id"),
                    rs.getString("source_system"),
                    rs.getString("external_id"),
                    rs.getString("container_external_id"),
                    rs.getString("container_name"),
                    rs.getString("title"),
                    rs.getString("api_path"),
                    rs.getString("web_url"),
                    parseInstant(rs.getString("source_updated_at")),
                    CatalogContentState.valueOf(rs.getString("content_state")),
                    parseInstant(rs.getString("content_indexed_at")),
                    Instant.parse(rs.getString("cataloged_at")));

    public void upsertContainer(String connectionId, String sourceSystem, String externalId,
                                String name, String webUrl) {
        if (externalId == null || externalId.isBlank()) return;
        List<String> ids = jdbc.queryForList("""
                SELECT id FROM connector_containers
                WHERE connection_id = ? AND source_system = ? AND external_id = ?
                """, String.class, connectionId, sourceSystem, externalId);
        String id = ids.isEmpty() ? UUID.randomUUID().toString() : ids.get(0);
        jdbc.update("""
                INSERT INTO connector_containers
                    (id, connection_id, source_system, external_id, name, web_url, cataloged_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(connection_id, source_system, external_id) DO UPDATE SET
                    name = excluded.name,
                    web_url = excluded.web_url,
                    cataloged_at = excluded.cataloged_at
                """, id, connectionId, sourceSystem, externalId,
                name == null || name.isBlank() ? externalId : name, webUrl, Instant.now().toString());
    }

    public Optional<String> findContainerName(String connectionId, String sourceSystem, String externalId) {
        if (externalId == null) return Optional.empty();
        List<String> names = jdbc.queryForList("""
                SELECT name FROM connector_containers
                WHERE connection_id = ? AND source_system = ? AND external_id = ?
                """, String.class, connectionId, sourceSystem, externalId);
        return names.stream().findFirst();
    }

    public UpsertResult upsertResource(String connectionId, String sourceSystem, String externalId,
                                       String containerExternalId, String containerName, String title,
                                       String apiPath, String webUrl, Instant sourceUpdatedAt) {
        CatalogResource existing = find(connectionId, sourceSystem, externalId).orElse(null);
        boolean changed = existing == null
                || !Objects.equals(existing.title(), title)
                || !Objects.equals(existing.containerExternalId(), containerExternalId)
                || !Objects.equals(existing.apiPath(), apiPath)
                || !Objects.equals(existing.webUrl(), webUrl)
                || !Objects.equals(existing.sourceUpdatedAt(), sourceUpdatedAt);
        CatalogContentState state = existing != null && !changed
                ? existing.contentState() : CatalogContentState.METADATA_ONLY;
        Instant indexedAt = existing != null && !changed ? existing.contentIndexedAt() : null;
        String id = existing == null ? UUID.randomUUID().toString() : existing.id();
        Instant now = Instant.now();

        jdbc.update("""
                INSERT INTO connector_resources
                    (id, connection_id, source_system, external_id, container_external_id,
                     container_name, title, api_path, web_url, source_updated_at, content_state,
                     content_indexed_at, cataloged_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(connection_id, source_system, external_id) DO UPDATE SET
                    container_external_id = excluded.container_external_id,
                    container_name = excluded.container_name,
                    title = excluded.title,
                    api_path = excluded.api_path,
                    web_url = excluded.web_url,
                    source_updated_at = excluded.source_updated_at,
                    content_state = excluded.content_state,
                    content_indexed_at = excluded.content_indexed_at,
                    cataloged_at = excluded.cataloged_at
                """, id, connectionId, sourceSystem, externalId, containerExternalId, containerName,
                title, apiPath, webUrl, sourceUpdatedAt == null ? null : sourceUpdatedAt.toString(),
                state.name(), indexedAt == null ? null : indexedAt.toString(), now.toString());
        replaceFts(id, title, containerName);
        CatalogResource saved = findById(id).orElseThrow();
        boolean invalidated = changed && existing != null
                && existing.contentState() == CatalogContentState.INDEXED;
        return new UpsertResult(saved, invalidated);
    }

    public Optional<CatalogResource> find(String connectionId, String sourceSystem, String externalId) {
        List<CatalogResource> rows = jdbc.query("""
                SELECT * FROM connector_resources
                WHERE connection_id = ? AND source_system = ? AND external_id = ?
                """, RESOURCE_MAPPER, connectionId, sourceSystem, externalId);
        return rows.stream().findFirst();
    }

    public Optional<CatalogResource> findById(String id) {
        List<CatalogResource> rows = jdbc.query(
                "SELECT * FROM connector_resources WHERE id = ?", RESOURCE_MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<CatalogResource> findTitleCandidates(String query, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        Map<String, CatalogResource> found = new LinkedHashMap<>();

        List<CatalogResource> exact = jdbc.query("""
                SELECT * FROM connector_resources
                WHERE lower(title) = lower(?) OR lower(external_id) = lower(?)
                ORDER BY cataloged_at DESC, title COLLATE NOCASE
                LIMIT ?
                """, RESOURCE_MAPPER, query.trim(), query.trim(), safeLimit);
        exact.forEach(resource -> found.put(resource.id(), resource));

        String ftsQuery = toFtsQuery(query);
        if (!ftsQuery.isBlank() && found.size() < safeLimit) {
            List<CatalogResource> matches = jdbc.query("""
                    SELECT r.*
                    FROM connector_resources_fts f
                    JOIN connector_resources r ON r.id = f.catalog_id
                    WHERE connector_resources_fts MATCH ?
                    ORDER BY bm25(connector_resources_fts), r.title COLLATE NOCASE
                    LIMIT ?
                    """, RESOURCE_MAPPER, ftsQuery, safeLimit);
            matches.forEach(resource -> found.putIfAbsent(resource.id(), resource));
        }
        return new ArrayList<>(found.values()).subList(0, Math.min(safeLimit, found.size()));
    }

    public void markIndexed(String id) {
        jdbc.update("""
                UPDATE connector_resources
                SET content_state = 'INDEXED', content_indexed_at = ?
                WHERE id = ?
                """, Instant.now().toString(), id);
    }

    public Set<String> findExternalIds(String connectionId, String sourceSystem) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT external_id FROM connector_resources
                WHERE connection_id = ? AND source_system = ?
                """, String.class, connectionId, sourceSystem));
    }

    public void beginInventory(String connectionId, String sourceSystem) {
        jdbc.update("DELETE FROM connector_inventory WHERE connection_id = ? AND source_system = ?",
                connectionId, sourceSystem);
    }

    public void recordInventoryId(String connectionId, String sourceSystem, String externalId) {
        if (externalId == null || externalId.isBlank()) return;
        jdbc.update("""
                INSERT OR IGNORE INTO connector_inventory (connection_id, source_system, external_id)
                VALUES (?, ?, ?)
                """, connectionId, sourceSystem, externalId);
    }

    /**
     * Finds catalogued or already-hydrated remote IDs absent from the completed streaming inventory.
     * UNION keeps migration-era chunks (created before the catalogue existed) eligible for cleanup.
     */
    public Set<String> findMissingFromInventory(String connectionId, String sourceSystem) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT r.external_id
                FROM connector_resources r
                WHERE r.connection_id = ? AND r.source_system = ?
                  AND NOT EXISTS (
                    SELECT 1 FROM connector_inventory i
                    WHERE i.connection_id = r.connection_id
                      AND i.source_system = r.source_system
                      AND i.external_id = r.external_id)
                UNION
                SELECT DISTINCT c.external_id
                FROM chunks c
                WHERE c.source_file_id LIKE ? AND c.source_system = ? AND c.external_id IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM connector_inventory i
                    WHERE i.connection_id = ? AND i.source_system = ?
                      AND i.external_id = c.external_id)
                """, String.class, connectionId, sourceSystem, connectionId + ":%", sourceSystem,
                connectionId, sourceSystem));
    }

    public void clearInventory(String connectionId, String sourceSystem) {
        jdbc.update("DELETE FROM connector_inventory WHERE connection_id = ? AND source_system = ?",
                connectionId, sourceSystem);
    }

    public long countResources() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM connector_resources", Long.class);
        return count == null ? 0 : count;
    }

    public void deleteResource(String connectionId, String sourceSystem, String externalId) {
        find(connectionId, sourceSystem, externalId).ifPresent(resource -> {
            jdbc.update("DELETE FROM connector_resources_fts WHERE catalog_id = ?", resource.id());
            jdbc.update("DELETE FROM connector_resources WHERE id = ?", resource.id());
        });
    }

    public void deleteByConnectionId(String connectionId) {
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM connector_resources WHERE connection_id = ?", String.class, connectionId);
        for (String id : ids) {
            jdbc.update("DELETE FROM connector_resources_fts WHERE catalog_id = ?", id);
        }
        jdbc.update("DELETE FROM connector_resources WHERE connection_id = ?", connectionId);
        jdbc.update("DELETE FROM connector_containers WHERE connection_id = ?", connectionId);
        jdbc.update("DELETE FROM connector_inventory WHERE connection_id = ?", connectionId);
    }

    private void replaceFts(String id, String title, String containerName) {
        jdbc.update("DELETE FROM connector_resources_fts WHERE catalog_id = ?", id);
        jdbc.update("""
                INSERT INTO connector_resources_fts (title, container_name, catalog_id)
                VALUES (?, ?, ?)
                """, title, containerName == null ? "" : containerName, id);
    }

    private static String toFtsQuery(String value) {
        Set<String> terms = TextSignals.terms(value);
        if (terms.isEmpty()) return "";
        List<String> quoted = terms.stream()
                .map(term -> "\"" + term.replace("\"", "\"\"") + "\"")
                .toList();
        return String.join(" OR ", quoted);
    }

    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
