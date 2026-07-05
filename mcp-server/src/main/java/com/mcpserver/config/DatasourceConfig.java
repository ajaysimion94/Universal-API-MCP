package com.mcpserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class DatasourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DatasourceConfig.class);

    @Bean
    public DataSource dataSource(@Value("${spring.datasource.url}") String url) {
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
        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
