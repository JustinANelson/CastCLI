package dev.justnels.castcli.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Transactional cross-process memory store. SQLite WAL and busy timeouts make concurrent MCP/CLI
 * clients safe; optimistic versions prevent lost updates.
 */
public final class SqliteMemoryStore implements MemoryStore {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final int MAX_CANDIDATES = 2_000;
    private final String jdbcUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final MemoryVectorizer vectorizer;

    public SqliteMemoryStore(Path databasePath) {
        this(databasePath, new HashingMemoryVectorizer());
    }

    public SqliteMemoryStore(Path databasePath, MemoryVectorizer vectorizer) {
        Path absolute = databasePath.toAbsolutePath().normalize();
        try {
            if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create memory directory", e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + absolute;
        this.vectorizer = vectorizer;
        initialize();
    }

    @Override
    public MemoryEntry put(MemoryDraft draft) {
        TenantSecurityContext.current().validateWriteAccess(draft.namespace());
        MemorySecurity.rejectSecrets(draft.content());
        String contentHash = contentHash(draft.topic(), draft.content());
        try (Connection connection = open()) {
            beginImmediate(connection);
            try {
                try (PreparedStatement purge = connection.prepareStatement(
                        "DELETE FROM memories WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
                    purge.setString(1, Instant.now().toString());
                    purge.executeUpdate();
                }
                Optional<MemoryEntry> duplicate = findDuplicate(connection, draft.namespace(), draft.scope(), contentHash);
                if (duplicate.isPresent()) {
                    rollback(connection);
                    return duplicate.get();
                }
                String id = UUID.randomUUID().toString();
                Instant now = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO memories
                        (id, namespace, scope, topic, content, author, source, tags, importance, confidence,
                         created_at, updated_at, expires_at, version, read_only, supersedes_id, content_hash, vector)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                        """)) {
                    bindDraft(statement, id, draft, now, now, contentHash);
                    statement.executeUpdate();
                }
                commit(connection);
                return getRequired(connection, id);
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (Exception e) {
            throw memoryFailure("create", e);
        }
    }

    @Override
    public MemoryEntry update(String id, int expectedVersion, MemoryDraft replacement) {
        MemorySecurity.rejectSecrets(replacement.content());
        try (Connection connection = open()) {
            beginImmediate(connection);
            try {
                MemoryEntry current = getRequired(connection, id);
                if (current.readOnly()) throw new IllegalStateException("Memory " + id + " is read-only");
                if (current.version() != expectedVersion) {
                    throw new IllegalStateException("Memory version conflict: expected " + expectedVersion + " but was " + current.version());
                }
                String contentHash = contentHash(replacement.topic(), replacement.content());
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE memories SET namespace=?, scope=?, topic=?, content=?, author=?, source=?, tags=?,
                          importance=?, confidence=?, updated_at=?, expires_at=?, version=version+1, read_only=?,
                          supersedes_id=?, content_hash=?, vector=? WHERE id=? AND version=?
                        """)) {
                    int index = 1;
                    statement.setString(index++, replacement.namespace());
                    statement.setString(index++, replacement.scope());
                    statement.setString(index++, replacement.topic().trim());
                    statement.setString(index++, replacement.content().trim());
                    statement.setString(index++, replacement.author());
                    statement.setString(index++, replacement.source());
                    statement.setString(index++, mapper.writeValueAsString(replacement.tags()));
                    statement.setDouble(index++, replacement.importance());
                    statement.setDouble(index++, replacement.confidence());
                    statement.setString(index++, Instant.now().toString());
                    setInstant(statement, index++, replacement.expiresAt());
                    statement.setInt(index++, replacement.readOnly() ? 1 : 0);
                    statement.setString(index++, replacement.supersedesId());
                    statement.setString(index++, contentHash);
                    statement.setBytes(index++, encode(vectorizer.vectorize(replacement.topic() + " " + replacement.content())));
                    statement.setString(index++, id);
                    statement.setInt(index, expectedVersion);
                    if (statement.executeUpdate() != 1) throw new IllegalStateException("Concurrent memory update detected");
                }
                commit(connection);
                return getRequired(connection, id);
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (Exception e) {
            throw memoryFailure("update", e);
        }
    }

    @Override
    public Optional<MemoryEntry> get(String id) {
        try (Connection connection = open()) {
            return get(connection, id);
        } catch (Exception e) {
            throw memoryFailure("read", e);
        }
    }

    @Override
    public List<MemoryEntry> search(MemoryQuery query) {
        try (Connection connection = open()) {
            List<MemoryEntry> candidates = loadCandidates(connection, query);
            float[] queryVector = vectorizer.vectorize(query.text());
            Set<String> terms = terms(query.text());
            return candidates.stream()
                    .filter(entry -> query.tags().isEmpty() || entry.tags().containsAll(query.tags()))
                    .map(entry -> new Scored(entry, score(entry, terms, queryVector)))
                    .filter(scored -> query.text().isBlank() || scored.score() > 0.02)
                    .sorted(Comparator.comparingDouble(Scored::score).reversed()
                            .thenComparing(scored -> scored.entry().updatedAt(), Comparator.reverseOrder()))
                    .limit(query.limit())
                    .map(Scored::entry)
                    .toList();
        } catch (Exception e) {
            throw memoryFailure("search", e);
        }
    }

    @Override
    public List<MemoryEntry> list(String namespace, int limit) {
        return search(new MemoryQuery("", namespace == null ? List.of() : List.of(namespace), null, List.of(), limit));
    }

    @Override
    public boolean delete(String id, int expectedVersion) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM memories WHERE id=? AND version=? AND read_only=0")) {
            statement.setString(1, id);
            statement.setInt(2, expectedVersion);
            return statement.executeUpdate() == 1;
        } catch (Exception e) {
            throw memoryFailure("delete", e);
        }
    }

    @Override
    public int purgeExpired() {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM memories WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
            statement.setString(1, Instant.now().toString());
            return statement.executeUpdate();
        } catch (Exception e) {
            throw memoryFailure("purge", e);
        }
    }

    @Override
    public int purgeOlderThan(int retentionDays) {
        if (retentionDays <= 0) return 0;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM memories WHERE read_only=0 AND updated_at < ?")) {
            statement.setString(1, Instant.now().minusSeconds(retentionDays * 86_400L).toString());
            return statement.executeUpdate();
        } catch (Exception e) {
            throw memoryFailure("apply retention policy to", e);
        }
    }

    private void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            SqliteMemoryMigrator.migrate(connection);
        } catch (Exception e) {
            throw memoryFailure("initialize", e);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=10000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private static void beginImmediate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute("BEGIN IMMEDIATE"); }
    }

    private static void commit(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute("COMMIT"); }
    }

    private static void rollback(Connection connection) {
        try (Statement statement = connection.createStatement()) { statement.execute("ROLLBACK"); }
        catch (SQLException ignored) { /* retain the original failure */ }
    }

    private List<MemoryEntry> loadCandidates(Connection connection, MemoryQuery query) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT * FROM memories WHERE (expires_at IS NULL OR expires_at > ?)");
        if (!query.namespaces().isEmpty()) sql.append(" AND namespace IN (").append("?,".repeat(query.namespaces().size()), 0, query.namespaces().size() * 2 - 1).append(')');
        if (query.scope() != null && !query.scope().isBlank()) sql.append(" AND scope=?");
        if (query.tenantId() != null && !query.tenantId().isBlank()) sql.append(" AND (author=? OR scope=?)");
        sql.append(" ORDER BY updated_at DESC LIMIT ").append(MAX_CANDIDATES);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, Instant.now().toString());
            for (String namespace : query.namespaces()) statement.setString(index++, namespace);
            if (query.scope() != null && !query.scope().isBlank()) statement.setString(index++, query.scope());
            if (query.tenantId() != null && !query.tenantId().isBlank()) {
                statement.setString(index++, query.tenantId());
                statement.setString(index++, query.tenantId());
            }
            try (ResultSet result = statement.executeQuery()) {
                List<MemoryEntry> entries = new ArrayList<>();
                while (result.next()) entries.add(map(result));
                return entries;
            }
        }
    }

    private Optional<MemoryEntry> findDuplicate(Connection connection, String namespace, String scope, String hash) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM memories WHERE namespace=? AND scope=? AND content_hash=?")) {
            statement.setString(1, namespace); statement.setString(2, scope); statement.setString(3, hash);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? Optional.of(map(result)) : Optional.empty(); }
        }
    }

    private Optional<MemoryEntry> get(Connection connection, String id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM memories WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? Optional.of(map(result)) : Optional.empty(); }
        }
    }

    private MemoryEntry getRequired(Connection connection, String id) throws Exception {
        return get(connection, id).orElseThrow(() -> new IllegalArgumentException("Unknown memory: " + id));
    }

    private MemoryEntry map(ResultSet result) throws Exception {
        String expiry = result.getString("expires_at");
        return new MemoryEntry(result.getString("id"), result.getString("namespace"), result.getString("scope"),
                result.getString("topic"), result.getString("content"), result.getString("author"),
                result.getString("source"), mapper.readValue(result.getString("tags"), STRING_LIST),
                result.getDouble("importance"), result.getDouble("confidence"),
                Instant.parse(result.getString("created_at")), Instant.parse(result.getString("updated_at")),
                expiry == null ? null : Instant.parse(expiry), result.getInt("version"),
                result.getInt("read_only") != 0, result.getString("supersedes_id"));
    }

    private void bindDraft(PreparedStatement statement, String id, MemoryDraft draft, Instant created, Instant updated,
                           String contentHash) throws Exception {
        int index = 1;
        statement.setString(index++, id); statement.setString(index++, draft.namespace()); statement.setString(index++, draft.scope());
        statement.setString(index++, draft.topic().trim()); statement.setString(index++, draft.content().trim());
        statement.setString(index++, draft.author()); statement.setString(index++, draft.source());
        statement.setString(index++, mapper.writeValueAsString(draft.tags())); statement.setDouble(index++, draft.importance());
        statement.setDouble(index++, draft.confidence()); statement.setString(index++, created.toString());
        statement.setString(index++, updated.toString()); setInstant(statement, index++, draft.expiresAt());
        statement.setInt(index++, draft.readOnly() ? 1 : 0); statement.setString(index++, draft.supersedesId());
        statement.setString(index++, contentHash);
        statement.setBytes(index, encode(vectorizer.vectorize(draft.topic() + " " + draft.content())));
    }

    private double score(MemoryEntry entry, Set<String> queryTerms, float[] queryVector) {
        if (queryTerms.isEmpty()) return entry.importance() * 0.6 + entry.confidence() * 0.4;
        Set<String> entryTerms = terms(entry.topic() + " " + entry.content() + " " + String.join(" ", entry.tags()));
        long overlap = queryTerms.stream().filter(entryTerms::contains).count();
        double lexical = overlap / (double) Math.max(1, queryTerms.size());
        double vector = cosine(queryVector, vectorizer.vectorize(entry.topic() + " " + entry.content()));
        return lexical * 0.45 + Math.max(0, vector) * 0.35 + entry.importance() * 0.12 + entry.confidence() * 0.08;
    }

    private static Set<String> terms(String text) {
        Set<String> terms = new HashSet<>();
        if (text != null) for (String term : text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) if (!term.isBlank()) terms.add(term);
        return terms;
    }

    private static double cosine(float[] left, float[] right) {
        double result = 0; for (int i = 0; i < Math.min(left.length, right.length); i++) result += left[i] * right[i]; return result;
    }

    private static byte[] encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) buffer.putFloat(value); return buffer.array();
    }

    private static String contentHash(String topic, String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((topic.trim() + "\n" + content.trim()).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void setInstant(PreparedStatement statement, int index, Instant instant) throws SQLException {
        if (instant == null) statement.setNull(index, java.sql.Types.VARCHAR); else statement.setString(index, instant.toString());
    }

    private static IllegalStateException memoryFailure(String operation, Exception cause) {
        if (cause instanceof IllegalStateException illegalState) return illegalState;
        if (cause instanceof IllegalArgumentException illegalArgument) throw illegalArgument;
        return new IllegalStateException("Failed to " + operation + " memory: " + cause.getMessage(), cause);
    }

    public void backup(Path destinationPath) {
        Path absolute = destinationPath.toAbsolutePath().normalize();
        try {
            if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
        } catch (Exception e) {
            throw memoryFailure("create backup directory", e);
        }
        try (Connection connection = open();
             Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + absolute.toString().replace("'", "''") + "'");
        } catch (Exception e) {
            throw memoryFailure("backup database", e);
        }
    }

    public void optimize() {
        try (Connection connection = open();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA optimize;");
            statement.execute("PRAGMA wal_checkpoint(PASSIVE);");
        } catch (Exception e) {
            throw memoryFailure("optimize database", e);
        }
    }

    public void checkpointWal() {
        try (Connection connection = open();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(PASSIVE);");
        } catch (Exception ignored) {
            // Passive checkpoint best-effort
        }
    }

    public void triggerAutoVacuumIfStale(dev.justnels.castcli.orchestration.HarnessOrchestrator orchestrator) {
        try {
            List<MemoryEntry> sessionEntries = list("session", 50);
            if (sessionEntries.size() > 15) {
                Thread.ofVirtual().start(() -> {
                    LocalMemoryCleaner cleaner = new LocalMemoryCleaner(this, orchestrator, "session");
                    cleaner.cleanAndConsolidate();
                });
            }
        } catch (Exception ignored) {
            // Best effort background auto-vacuum
        }
    }

    private record Scored(MemoryEntry entry, double score) { }
}
