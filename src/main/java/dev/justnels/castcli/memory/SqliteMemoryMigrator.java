package dev.justnels.castcli.memory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

/**
 * Manages database schema migrations for {@link SqliteMemoryStore}, tracking version
 * history in the {@code schema_version} table.
 */
public final class SqliteMemoryMigrator {

    public static final int CURRENT_VERSION = 1;

    @FunctionalInterface
    public interface MigrationStep {
        void apply(Connection connection) throws SQLException;
    }

    private static final List<MigrationStep> MIGRATIONS = List.of(
            // Version 1: Initial memories table and indexes
            connection -> {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("""
                            CREATE TABLE IF NOT EXISTS memories (
                              id TEXT PRIMARY KEY, namespace TEXT NOT NULL, scope TEXT NOT NULL, topic TEXT NOT NULL,
                              content TEXT NOT NULL, author TEXT NOT NULL, source TEXT NOT NULL, tags TEXT NOT NULL,
                              importance REAL NOT NULL, confidence REAL NOT NULL, created_at TEXT NOT NULL,
                              updated_at TEXT NOT NULL, expires_at TEXT, version INTEGER NOT NULL, read_only INTEGER NOT NULL,
                              supersedes_id TEXT, content_hash TEXT NOT NULL, vector BLOB NOT NULL
                            )
                            """);
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_namespace ON memories(namespace, scope, updated_at DESC)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_expiry ON memories(expires_at)");
                    stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_dedupe ON memories(namespace, scope, content_hash)");
                }
            }
    );

    private SqliteMemoryMigrator() {
    }

    /**
     * Executes pending database schema migrations on the provided connection.
     *
     * @param connection active SQLite JDBC connection
     * @throws SQLException if a migration step fails
     */
    public static void migrate(Connection connection) throws SQLException {
        ensureSchemaVersionTable(connection);
        int currentDbVersion = getCurrentDbVersion(connection);

        for (int v = currentDbVersion + 1; v <= MIGRATIONS.size(); v++) {
            MigrationStep step = MIGRATIONS.get(v - 1);
            step.apply(connection);

            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)")) {
                stmt.setInt(1, v);
                stmt.setString(2, Instant.now().toString());
                stmt.executeUpdate();
            }
        }
    }

    private static void ensureSchemaVersionTable(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INTEGER PRIMARY KEY,
                        applied_at TEXT NOT NULL
                    )
                    """);
        }
    }

    public static int getCurrentDbVersion(Connection connection) throws SQLException {
        ensureSchemaVersionTable(connection);
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_version")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        }
    }
}
