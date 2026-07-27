package dev.justnels.castcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteMemoryMigratorTest {

    @Test
    void appliesMigrationsOnFreshDatabase(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("test_memory.db");
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            assertThat(SqliteMemoryMigrator.getCurrentDbVersion(connection)).isEqualTo(0);
            SqliteMemoryMigrator.migrate(connection);
            assertThat(SqliteMemoryMigrator.getCurrentDbVersion(connection)).isEqualTo(SqliteMemoryMigrator.CURRENT_VERSION);
        }
    }

    @Test
    void migrationIsIdempotent(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("test_memory.db");
        SqliteMemoryStore store = new SqliteMemoryStore(dbPath);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            assertThat(SqliteMemoryMigrator.getCurrentDbVersion(connection)).isEqualTo(SqliteMemoryMigrator.CURRENT_VERSION);
            // Running migrate again should do nothing and succeed cleanly
            SqliteMemoryMigrator.migrate(connection);
            assertThat(SqliteMemoryMigrator.getCurrentDbVersion(connection)).isEqualTo(SqliteMemoryMigrator.CURRENT_VERSION);
        }
    }
}
