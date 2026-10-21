package com.example.agent.service.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real SQLite migrations ({@code db/migration/sqlite}) against a fresh file, as the
 * {@code storage-sqlite} profile does at startup. The SQLite integration tests build their schema
 * with Flyway off, so without this a broken migration would pass the build and fail at startup.
 */
class SqliteMigrationsTest {

    @TempDir Path dir;

    @Test
    void everyMigrationAppliesAndTheSessionTableHasThePlanColumn() throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("agent.db");
        Flyway.configure()
                .dataSource(url, null, null)
                .locations("classpath:db/migration/sqlite")
                .group(false)
                .load()
                .migrate();

        List<String> columns = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url);
             ResultSet rs = c.createStatement().executeQuery("PRAGMA table_info(agent_sessions)")) {
            while (rs.next()) columns.add(rs.getString("name"));
        }
        assertThat(columns).contains("id", "title", "created_at", "user_id", "plan_json");
    }
}
