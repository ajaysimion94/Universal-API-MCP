package com.mcpserver.repositories;

import com.mcpserver.models.Chunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkRepositorySearchTests {

    private SingleConnectionDataSource dataSource;
    private ChunkRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE chunks (
                  id TEXT PRIMARY KEY,
                  source_file_id TEXT NOT NULL,
                  source_name TEXT NOT NULL,
                  source_path TEXT,
                  content TEXT NOT NULL,
                  embedding TEXT,
                  acl_tags TEXT NOT NULL,
                  position INTEGER NOT NULL,
                  token_count INTEGER NOT NULL,
                  created_at TEXT NOT NULL DEFAULT '1970-01-01T00:00:00Z',
                  source_system TEXT,
                  external_id TEXT,
                  url TEXT,
                  updated_at TEXT
                )
                """);
        jdbc.execute("CREATE VIRTUAL TABLE chunks_fts USING fts5(content, chunk_id UNINDEXED)");
        repository = new ChunkRepository(jdbc);
        repository.save(Chunk.create(
                "incident-file", "incident-runbook.md", "/runbooks/incident-runbook.md",
                "INC-1042 documents the database outage. The SLA is four hours.",
                null, List.of("public"), 0, 20));
        repository.save(Chunk.create(
                "cooking-file", "cooking.md", "/personal/cooking.md",
                "Bake sourdough bread with flour and water.",
                null, List.of("public"), 0, 10));
    }

    @AfterEach
    void close() {
        dataSource.destroy();
    }

    @Test
    void exactTicketIdSurvivesFtsNormalization() {
        assertThat(repository.lexicalSearch("INC-1042", 5))
                .extracting(Chunk::sourceName)
                .containsExactly("incident-runbook.md");
    }

    @Test
    void acronymRetrievesThroughTheLexicalLeg() {
        assertThat(repository.lexicalSearch("SLA", 5))
                .extracting(Chunk::sourceName)
                .containsExactly("incident-runbook.md");
    }

    @Test
    void naturalLanguageDoesNotRequireEveryQuestionWordToExist() {
        assertThat(repository.lexicalSearch("What is the SLA for incident response?", 5))
                .extracting(Chunk::sourceName)
                .containsExactly("incident-runbook.md");
    }
}
