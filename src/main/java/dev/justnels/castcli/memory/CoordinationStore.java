package dev.justnels.castcli.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional project coordination state stored beside CastCLI memory. Task claims use SQLite
 * immediate transactions so independent MCP and CLI processes cannot acquire the same live lease.
 */
public final class CoordinationStore implements AutoCloseable {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final String PROJECT_KEY = "default";
    private static final int MAX_LIST_RESULTS = 200;

    private final String jdbcUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public CoordinationStore(Path databasePath) {
        Path absolute = databasePath.toAbsolutePath().normalize();
        try {
            if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create coordination directory", exception);
        }
        jdbcUrl = "jdbc:sqlite:" + absolute;
        initialize();
    }

    public ProjectState setProjectState(int expectedVersion, String objective, String phase,
                                        List<String> decisions, List<String> blockers, String author) {
        requireText("objective", objective);
        requireText("phase", phase);
        requireText("author", author);
        rejectSecrets(objective, phase, author);
        rejectSecrets(safeList(decisions));
        rejectSecrets(safeList(blockers));
        try (Connection connection = open()) {
            beginImmediate(connection);
            try {
                Optional<ProjectState> current = projectState(connection);
                Instant now = Instant.now();
                if (current.isEmpty()) {
                    if (expectedVersion != 0) {
                        throw new IllegalStateException("Project state does not exist; expectedVersion must be 0");
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO coordination_project_state
                            (project_key, objective, phase, decisions, blockers, author, version, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                            """)) {
                        statement.setString(1, PROJECT_KEY);
                        statement.setString(2, objective.trim());
                        statement.setString(3, phase.trim());
                        statement.setString(4, json(decisions));
                        statement.setString(5, json(blockers));
                        statement.setString(6, author.trim());
                        statement.setString(7, now.toString());
                        statement.executeUpdate();
                    }
                } else {
                    if (current.get().version() != expectedVersion) {
                        throw new IllegalStateException("Project state version conflict: expected " + expectedVersion
                                + " but was " + current.get().version());
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE coordination_project_state
                            SET objective=?, phase=?, decisions=?, blockers=?, author=?, version=version+1, updated_at=?
                            WHERE project_key=? AND version=?
                            """)) {
                        statement.setString(1, objective.trim());
                        statement.setString(2, phase.trim());
                        statement.setString(3, json(decisions));
                        statement.setString(4, json(blockers));
                        statement.setString(5, author.trim());
                        statement.setString(6, now.toString());
                        statement.setString(7, PROJECT_KEY);
                        statement.setInt(8, expectedVersion);
                        if (statement.executeUpdate() != 1) {
                            throw new IllegalStateException("Concurrent project state update detected");
                        }
                    }
                }
                commit(connection);
                return projectState(connection).orElseThrow();
            } catch (Exception exception) {
                rollback(connection);
                throw exception;
            }
        } catch (Exception exception) {
            throw failure("update project state", exception);
        }
    }

    public Optional<ProjectState> projectState() {
        try (Connection connection = open()) {
            return projectState(connection);
        } catch (Exception exception) {
            throw failure("read project state", exception);
        }
    }

    public CoordinationTask createTask(String title, String description, List<String> dependencies,
                                       List<String> expectedFiles, String createdBy) {
        requireText("title", title);
        requireText("createdBy", createdBy);
        rejectSecrets(title, description, createdBy);
        List<String> safeDependencies = normalizedValues(dependencies);
        List<String> safeExpectedFiles = normalizedPaths(expectedFiles);
        try (Connection connection = open()) {
            beginImmediate(connection);
            try {
                for (String dependency : safeDependencies) getRequired(connection, dependency);
                String id = UUID.randomUUID().toString();
                Instant now = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO coordination_tasks
                        (id, title, description, status, owner, branch, worktree, expected_files, dependencies,
                         lease_expires_at, created_by, notes, version, created_at, updated_at)
                        VALUES (?, ?, ?, 'OPEN', NULL, NULL, NULL, ?, ?, NULL, ?, '', 1, ?, ?)
                        """)) {
                    statement.setString(1, id);
                    statement.setString(2, title.trim());
                    statement.setString(3, trim(description));
                    statement.setString(4, json(safeExpectedFiles));
                    statement.setString(5, json(safeDependencies));
                    statement.setString(6, createdBy.trim());
                    statement.setString(7, now.toString());
                    statement.setString(8, now.toString());
                    statement.executeUpdate();
                }
                commit(connection);
                return getRequired(connection, id);
            } catch (Exception exception) {
                rollback(connection);
                throw exception;
            }
        } catch (Exception exception) {
            throw failure("create coordination task", exception);
        }
    }

    public ClaimResult claimTask(String taskId, String owner, int leaseMinutes, String branch, String worktree) {
        requireText("taskId", taskId);
        requireText("owner", owner);
        if (leaseMinutes < 1 || leaseMinutes > 1_440) {
            throw new IllegalArgumentException("leaseMinutes must be between 1 and 1440");
        }
        rejectSecrets(owner, branch, worktree);
        try (Connection connection = open()) {
            beginImmediate(connection);
            try {
                CoordinationTask task = getRequired(connection, taskId);
                Instant now = Instant.now();
                if ("COMPLETE".equals(task.status())) {
                    throw new IllegalStateException("Task " + taskId + " is already complete");
                }
                if (hasLiveLease(task, now) && !owner.equals(task.owner())) {
                    throw new IllegalStateException("Task " + taskId + " is leased by " + task.owner()
                            + " until " + task.leaseExpiresAt());
                }
                List<String> incomplete = incompleteDependencies(connection, task.dependencies());
                if (!incomplete.isEmpty()) {
                    throw new IllegalStateException("Task dependencies are incomplete: " + String.join(", ", incomplete));
                }
                List<String> warnings = overlapWarnings(connection, task, owner, now);
                Instant leaseExpiresAt = now.plus(Duration.ofMinutes(leaseMinutes));
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE coordination_tasks SET status='IN_PROGRESS', owner=?, branch=?, worktree=?,
                          lease_expires_at=?, version=version+1, updated_at=? WHERE id=? AND version=?
                        """)) {
                    statement.setString(1, owner.trim());
                    statement.setString(2, blankToNull(branch));
                    statement.setString(3, blankToNull(worktree));
                    statement.setString(4, leaseExpiresAt.toString());
                    statement.setString(5, now.toString());
                    statement.setString(6, taskId);
                    statement.setInt(7, task.version());
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("Concurrent task claim detected");
                    }
                }
                commit(connection);
                return new ClaimResult(getRequired(connection, taskId), warnings);
            } catch (Exception exception) {
                rollback(connection);
                throw exception;
            }
        } catch (Exception exception) {
            throw failure("claim coordination task", exception);
        }
    }

    public CoordinationTask heartbeatTask(String taskId, String owner, int leaseMinutes) {
        requireText("taskId", taskId);
        requireText("owner", owner);
        if (leaseMinutes < 1 || leaseMinutes > 1_440) {
            throw new IllegalArgumentException("leaseMinutes must be between 1 and 1440");
        }
        try (Connection connection = open()) {
            beginImmediate(connection);
            try {
                CoordinationTask task = getRequired(connection, taskId);
                if (!"IN_PROGRESS".equals(task.status()) || !owner.equals(task.owner())) {
                    throw new IllegalStateException("Only the current task owner can renew an in-progress lease");
                }
                Instant now = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE coordination_tasks SET lease_expires_at=?, version=version+1, updated_at=?
                        WHERE id=? AND version=? AND owner=?
                        """)) {
                    statement.setString(1, now.plus(Duration.ofMinutes(leaseMinutes)).toString());
                    statement.setString(2, now.toString());
                    statement.setString(3, taskId);
                    statement.setInt(4, task.version());
                    statement.setString(5, owner);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("Concurrent task heartbeat detected");
                    }
                }
                commit(connection);
                return getRequired(connection, taskId);
            } catch (Exception exception) {
                rollback(connection);
                throw exception;
            }
        } catch (Exception exception) {
            throw failure("renew coordination task lease", exception);
        }
    }

    public HandoffResult handoffTask(String taskId, String owner, int expectedVersion, String status,
                                     String sessionId, String summary, List<String> filesChanged,
                                     List<String> testsRun, List<String> failures, String nextAction,
                                     String commitRef) {
        requireText("taskId", taskId);
        requireText("owner", owner);
        requireText("sessionId", sessionId);
        requireText("summary", summary);
        String normalizedStatus = normalizeHandoffStatus(status);
        rejectSecrets(owner, sessionId, summary, nextAction, commitRef);
        rejectSecrets(safeList(filesChanged));
        rejectSecrets(safeList(testsRun));
        rejectSecrets(safeList(failures));
        try (Connection connection = open()) {
            beginImmediate(connection);
            try {
                CoordinationTask task = getRequired(connection, taskId);
                if (task.version() != expectedVersion) {
                    throw new IllegalStateException("Task version conflict: expected " + expectedVersion
                            + " but was " + task.version());
                }
                if (!owner.equals(task.owner())) {
                    throw new IllegalStateException("Only the current task owner can record its handoff");
                }
                String handoffId = UUID.randomUUID().toString();
                Instant now = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO coordination_handoffs
                        (id, task_id, session_id, agent, summary, files_changed, tests_run, failures,
                         next_action, commit_ref, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, handoffId);
                    statement.setString(2, taskId);
                    statement.setString(3, sessionId.trim());
                    statement.setString(4, owner.trim());
                    statement.setString(5, summary.trim());
                    statement.setString(6, json(filesChanged));
                    statement.setString(7, json(testsRun));
                    statement.setString(8, json(failures));
                    statement.setString(9, trim(nextAction));
                    statement.setString(10, trim(commitRef));
                    statement.setString(11, now.toString());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE coordination_tasks SET status=?, owner=NULL, lease_expires_at=NULL, notes=?,
                          version=version+1, updated_at=? WHERE id=? AND version=? AND owner=?
                        """)) {
                    statement.setString(1, normalizedStatus);
                    statement.setString(2, summary.trim());
                    statement.setString(3, now.toString());
                    statement.setString(4, taskId);
                    statement.setInt(5, expectedVersion);
                    statement.setString(6, owner);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("Concurrent task handoff detected");
                    }
                }
                commit(connection);
                return new HandoffResult(getRequired(connection, taskId), getHandoff(connection, handoffId));
            } catch (Exception exception) {
                rollback(connection);
                throw exception;
            }
        } catch (Exception exception) {
            throw failure("record coordination handoff", exception);
        }
    }

    public CoordinationSnapshot snapshot(int taskLimit, int handoffLimit) {
        int safeTaskLimit = boundedLimit(taskLimit);
        int safeHandoffLimit = boundedLimit(handoffLimit);
        try (Connection connection = open()) {
            List<CoordinationTask> tasks = listTasks(connection, safeTaskLimit);
            List<Handoff> handoffs = listHandoffs(connection, safeHandoffLimit);
            Instant now = Instant.now();
            List<String> warnings = tasks.stream()
                    .filter(task -> "IN_PROGRESS".equals(task.status()))
                    .filter(task -> task.leaseExpiresAt() == null || !task.leaseExpiresAt().isAfter(now))
                    .map(task -> "STALE_LEASE task=" + task.id() + " previousOwner=" + task.owner())
                    .toList();
            return new CoordinationSnapshot(projectState(connection).orElse(null), tasks, handoffs, warnings);
        } catch (Exception exception) {
            throw failure("read coordination snapshot", exception);
        }
    }

    public Optional<CoordinationTask> getTask(String id) {
        try (Connection connection = open()) {
            return getTask(connection, id);
        } catch (Exception exception) {
            throw failure("read coordination task", exception);
        }
    }

    private void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            SqliteMemoryMigrator.migrate(connection);
        } catch (Exception exception) {
            throw failure("initialize coordination store", exception);
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

    private Optional<ProjectState> projectState(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM coordination_project_state WHERE project_key=?")) {
            statement.setString(1, PROJECT_KEY);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new ProjectState(result.getString("objective"), result.getString("phase"),
                        strings(result.getString("decisions")), strings(result.getString("blockers")),
                        result.getString("author"), result.getInt("version"),
                        Instant.parse(result.getString("updated_at"))));
            }
        }
    }

    private Optional<CoordinationTask> getTask(Connection connection, String id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM coordination_tasks WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapTask(result)) : Optional.empty();
            }
        }
    }

    private CoordinationTask getRequired(Connection connection, String id) throws Exception {
        return getTask(connection, id).orElseThrow(() -> new IllegalArgumentException("Unknown coordination task: " + id));
    }

    private List<CoordinationTask> listTasks(Connection connection, int limit) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM coordination_tasks ORDER BY updated_at DESC LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<CoordinationTask> tasks = new ArrayList<>();
                while (result.next()) tasks.add(mapTask(result));
                return tasks;
            }
        }
    }

    private List<Handoff> listHandoffs(Connection connection, int limit) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM coordination_handoffs ORDER BY created_at DESC LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<Handoff> handoffs = new ArrayList<>();
                while (result.next()) handoffs.add(mapHandoff(result));
                return handoffs;
            }
        }
    }

    private Handoff getHandoff(Connection connection, String id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM coordination_handoffs WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Unknown coordination handoff: " + id);
                return mapHandoff(result);
            }
        }
    }

    private List<String> incompleteDependencies(Connection connection, List<String> dependencies) throws Exception {
        List<String> incomplete = new ArrayList<>();
        for (String dependency : dependencies) {
            CoordinationTask task = getRequired(connection, dependency);
            if (!"COMPLETE".equals(task.status())) incomplete.add(dependency);
        }
        return incomplete;
    }

    private List<String> overlapWarnings(Connection connection, CoordinationTask claimed, String owner,
                                         Instant now) throws Exception {
        if (claimed.expectedFiles().isEmpty()) return List.of();
        List<String> warnings = new ArrayList<>();
        for (CoordinationTask active : listTasks(connection, MAX_LIST_RESULTS)) {
            if (active.id().equals(claimed.id()) || owner.equals(active.owner()) || !hasLiveLease(active, now)) continue;
            List<String> overlaps = new ArrayList<>();
            for (String left : claimed.expectedFiles()) {
                for (String right : active.expectedFiles()) {
                    if (pathsOverlap(left, right)) overlaps.add(left + " <> " + right);
                }
            }
            if (!overlaps.isEmpty()) {
                warnings.add("FILE_OVERLAP task=" + active.id() + " owner=" + active.owner()
                        + " paths=" + String.join(", ", overlaps));
            }
        }
        return List.copyOf(warnings);
    }

    private CoordinationTask mapTask(ResultSet result) throws Exception {
        String lease = result.getString("lease_expires_at");
        return new CoordinationTask(result.getString("id"), result.getString("title"),
                result.getString("description"), result.getString("status"), result.getString("owner"),
                result.getString("branch"), result.getString("worktree"),
                strings(result.getString("expected_files")), strings(result.getString("dependencies")),
                lease == null ? null : Instant.parse(lease), result.getString("created_by"),
                result.getString("notes"), result.getInt("version"),
                Instant.parse(result.getString("created_at")), Instant.parse(result.getString("updated_at")));
    }

    private Handoff mapHandoff(ResultSet result) throws Exception {
        return new Handoff(result.getString("id"), result.getString("task_id"),
                result.getString("session_id"), result.getString("agent"), result.getString("summary"),
                strings(result.getString("files_changed")), strings(result.getString("tests_run")),
                strings(result.getString("failures")), result.getString("next_action"),
                result.getString("commit_ref"), Instant.parse(result.getString("created_at")));
    }

    private String json(List<String> values) throws Exception {
        return mapper.writeValueAsString(safeList(values));
    }

    private List<String> strings(String json) throws Exception {
        return mapper.readValue(json, STRING_LIST);
    }

    private static boolean hasLiveLease(CoordinationTask task, Instant now) {
        return "IN_PROGRESS".equals(task.status()) && task.owner() != null && task.leaseExpiresAt() != null
                && task.leaseExpiresAt().isAfter(now);
    }

    private static boolean pathsOverlap(String left, String right) {
        String leftPrefix = wildcardPrefix(left);
        String rightPrefix = wildcardPrefix(right);
        return leftPrefix.equals(rightPrefix) || leftPrefix.startsWith(withSlash(rightPrefix))
                || rightPrefix.startsWith(withSlash(leftPrefix));
    }

    private static String wildcardPrefix(String value) {
        int wildcard = value.indexOf('*');
        String prefix = wildcard < 0 ? value : value.substring(0, wildcard);
        return prefix.replaceAll("/+$", "");
    }

    private static String withSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    private static List<String> normalizedPaths(List<String> values) {
        return safeList(values).stream().map(value -> value.trim().replace('\\', '/'))
                .filter(value -> !value.isBlank()).map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
    }

    private static List<String> normalizedValues(List<String> values) {
        return safeList(values).stream().map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void rejectSecrets(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) MemorySecurity.rejectSecrets(value);
    }

    private static void rejectSecrets(List<String> values) {
        for (String value : values) rejectSecrets(value);
    }

    private static void requireText(String field, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeHandoffStatus(String status) {
        String normalized = trim(status).toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "BLOCKED", "COMPLETE").contains(normalized)) {
            throw new IllegalArgumentException("handoff status must be OPEN, BLOCKED, or COMPLETE");
        }
        return normalized;
    }

    private static int boundedLimit(int limit) {
        return Math.max(1, Math.min(limit <= 0 ? 20 : limit, MAX_LIST_RESULTS));
    }

    private static void beginImmediate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
        }
    }

    private static void commit(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("COMMIT");
        }
    }

    private static void rollback(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }

    private static IllegalStateException failure(String operation, Exception cause) {
        if (cause instanceof IllegalStateException illegalState) return illegalState;
        if (cause instanceof IllegalArgumentException illegalArgument) throw illegalArgument;
        return new IllegalStateException("Failed to " + operation + ": " + cause.getMessage(), cause);
    }

    @Override
    public void close() {
        // Connections are opened per operation for cross-process safety.
    }

    public record ProjectState(String objective, String phase, List<String> decisions, List<String> blockers,
                               String author, int version, Instant updatedAt) { }

    public record CoordinationTask(String id, String title, String description, String status, String owner,
                                   String branch, String worktree, List<String> expectedFiles,
                                   List<String> dependencies, Instant leaseExpiresAt, String createdBy,
                                   String notes, int version, Instant createdAt, Instant updatedAt) { }

    public record Handoff(String id, String taskId, String sessionId, String agent, String summary,
                          List<String> filesChanged, List<String> testsRun, List<String> failures,
                          String nextAction, String commitRef, Instant createdAt) { }

    public record ClaimResult(CoordinationTask task, List<String> warnings) { }

    public record HandoffResult(CoordinationTask task, Handoff handoff) { }

    public record CoordinationSnapshot(ProjectState projectState, List<CoordinationTask> tasks,
                                       List<Handoff> handoffs, List<String> warnings) { }
}
