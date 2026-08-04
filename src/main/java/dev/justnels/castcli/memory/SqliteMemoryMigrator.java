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

    public static final int CURRENT_VERSION = 2;

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
            },
            // Version 2: Structured multi-agent coordination state, task leases, and handoffs
            connection -> {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("""
                            CREATE TABLE IF NOT EXISTS coordination_project_state (
                              project_key TEXT PRIMARY KEY, objective TEXT NOT NULL, phase TEXT NOT NULL,
                              decisions TEXT NOT NULL, blockers TEXT NOT NULL, author TEXT NOT NULL,
                              version INTEGER NOT NULL, updated_at TEXT NOT NULL
                            )
                            """);
                    stmt.execute("""
                            CREATE TABLE IF NOT EXISTS coordination_tasks (
                              id TEXT PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL,
                              status TEXT NOT NULL, owner TEXT, branch TEXT, worktree TEXT,
                              expected_files TEXT NOT NULL, dependencies TEXT NOT NULL,
                              lease_expires_at TEXT, created_by TEXT NOT NULL, notes TEXT NOT NULL,
                              version INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                            )
                            """);
                    stmt.execute("""
                            CREATE TABLE IF NOT EXISTS coordination_handoffs (
                              id TEXT PRIMARY KEY, task_id TEXT NOT NULL, session_id TEXT NOT NULL,
                              agent TEXT NOT NULL, summary TEXT NOT NULL, files_changed TEXT NOT NULL,
                              tests_run TEXT NOT NULL, failures TEXT NOT NULL, next_action TEXT NOT NULL,
                              commit_ref TEXT NOT NULL, created_at TEXT NOT NULL,
                              FOREIGN KEY(task_id) REFERENCES coordination_tasks(id)
                            )
                            """);
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_coordination_tasks_status "
                            + "ON coordination_tasks(status, updated_at DESC)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_coordination_tasks_owner "
                            + "ON coordination_tasks(owner, lease_expires_at)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_coordination_handoffs_task "
                            + "ON coordination_handoffs(task_id, created_at DESC)");
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
