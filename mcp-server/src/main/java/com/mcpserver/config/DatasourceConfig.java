package com.mcpserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
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
import java.util.Optional;

@Configuration
public class DatasourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DatasourceConfig.class);

    @Bean
    public DataSource dataSource(@Value("${spring.datasource.url}") String url) throws SQLException {
        Optional<Path> parent = databaseParentDirectory(url);
        if (parent.isPresent()) {
            try {
                Files.createDirectories(parent.get());
            } catch (Exception e) {
                log.warn("Failed to create data directory {}: {}", parent.get(), e.getMessage());
            }
        }
        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.enforceForeignKeys(true);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl(url);
        var connection = ds.getConnection();
        runSchema(connection);
        // sqlite-vec is a loadable extension, and SQLite extensions are scoped to
        // a connection. Keep one shared embedded connection so vec0 stays loaded
        // for ingestion and search after the plugin activates it.
        return new SingleConnectionDataSource(connection, true);
    }

    /**
     * Returns the parent directory only for a plain filesystem-backed SQLite URL.
     *
     * <p>SQLite also accepts non-filesystem locations such as {@code :memory:},
     * {@code file::memory:?cache=shared}, and {@code :resource:...}. Passing those values to
     * {@link Path#of(String, String...)} happens to work as a filename on Unix, but fails on
     * Windows because a colon is not legal at index zero. SQLite itself understands these values,
     * so directory preparation must leave them untouched.
     */
    static Optional<Path> databaseParentDirectory(String url) {
        final String prefix = "jdbc:sqlite:";
        if (url == null || !url.startsWith(prefix)) {
            throw new IllegalArgumentException("SQLite datasource URL must start with " + prefix);
        }
        String location = url.substring(prefix.length());
        if (location.isBlank() || location.startsWith(":") || location.startsWith("file:")) {
            return Optional.empty();
        }
        return Optional.ofNullable(Path.of(location).getParent());
    }

    private void runSchema(java.sql.Connection connection) {
        try {
            var resource = new ClassPathResource("schema.sql");
            if (!resource.exists()) {
                log.warn("schema.sql not found on classpath — tables must exist already");
                return;
            }
            log.info("Running schema.sql...");
            String sql;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(resource.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                var sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                sql = sb.toString();
            }
            // Strip single-line comments (-- to end of line) from the whole document first,
            // so a semicolon inside a comment can't be mistaken for a statement separator.
            String uncommented = sql.replaceAll("(?m)--.*$", "");
            // Execute each statement separated by semicolons.
            for (String raw : uncommented.split(";")) {
                String cleaned = raw.trim();
                if (cleaned.isEmpty()) continue;
                // Collapse newlines for logging
                log.debug("Executing: {}...", cleaned.substring(0, Math.min(80, cleaned.length())));
                try (var s = connection.createStatement()) {
                    s.execute(cleaned);
                } catch (Exception e) {
                    log.warn("Schema statement skipped (continue-on-error): {}", e.getMessage());
                }
            }
            log.info("Schema initialized from schema.sql");
        } catch (Exception e) {
            log.warn("Failed to initialize schema: {}", e.getMessage());
        }
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
