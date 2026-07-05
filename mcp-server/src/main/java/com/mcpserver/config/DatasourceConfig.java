package com.mcpserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

@Configuration
public class DatasourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DatasourceConfig.class);

    @Bean
    public DataSource dataSource(@Value("${spring.datasource.url}") String url) throws SQLException {
        String dbPath = url.replace("jdbc:sqlite:", "");
        Path parent = Path.of(dbPath).getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (Exception e) {
                log.warn("Failed to create data directory {}: {}", parent, e.getMessage());
            }
        }
        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.enforceForeignKeys(true);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl(url);
        // sqlite-vec is a loadable extension, and SQLite extensions are scoped to
        // a connection. Keep one shared embedded connection so vec0 stays loaded
        // for ingestion and search after the plugin activates it.
        return new SingleConnectionDataSource(ds.getConnection(), true);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
