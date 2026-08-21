package com.mcpserver.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasourceConfigTests {

    @Test
    void inMemoryDatabaseUrlBypassesFilesystemPathParsing() throws Exception {
        DatasourceConfig config = new DatasourceConfig();
        DataSource dataSource = config.dataSource("jdbc:sqlite::memory:");
        try {
            assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class))
                    .isEqualTo(1);
        } finally {
            ((SingleConnectionDataSource) dataSource).destroy();
        }
    }

    @Test
    void sqliteSpecialLocationsDoNotHaveFilesystemDirectories() {
        assertThat(DatasourceConfig.databaseParentDirectory("jdbc:sqlite::memory:")).isEmpty();
        assertThat(DatasourceConfig.databaseParentDirectory(
                "jdbc:sqlite:file::memory:?cache=shared")).isEmpty();
        assertThat(DatasourceConfig.databaseParentDirectory(
                "jdbc:sqlite::resource:seed.db")).isEmpty();
    }

    @Test
    void fileBackedDatabaseStillPreparesItsParentDirectory() {
        assertThat(DatasourceConfig.databaseParentDirectory("jdbc:sqlite:./data/test.db"))
                .contains(Path.of("./data"));
    }

    @Test
    void rejectsNonSqliteDatasourceUrlsClearly() {
        assertThatThrownBy(() -> DatasourceConfig.databaseParentDirectory("memory:test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbc:sqlite:");
    }
}
