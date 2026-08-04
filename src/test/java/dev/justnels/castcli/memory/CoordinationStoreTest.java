package dev.justnels.castcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoordinationStoreTest {

    @Test
    void projectStateUsesOptimisticVersioning(@TempDir Path tempDir) {
        try (CoordinationStore store = new CoordinationStore(tempDir.resolve("memory.db"))) {
            CoordinationStore.ProjectState created = store.setProjectState(0, "Ship coordination", "BUILD",
                    List.of("SQLite is canonical"), List.of("None"), "Codex");

            assertThat(created.version()).isEqualTo(1);
            assertThat(store.projectState()).contains(created);
            assertThatThrownBy(() -> store.setProjectState(0, "Stale", "BUILD", List.of(), List.of(), "Claude"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("version conflict");

            CoordinationStore.ProjectState updated = store.setProjectState(1, "Ship coordination", "VERIFY",
                    List.of("SQLite is canonical", "Leases are atomic"), List.of(), "Claude");
            assertThat(updated.version()).isEqualTo(2);
            assertThat(updated.phase()).isEqualTo("VERIFY");
        }
    }

    @Test
    void dependenciesClaimsHeartbeatsAndStructuredHandoffsFormOneWorkflow(@TempDir Path tempDir) {
        try (CoordinationStore store = new CoordinationStore(tempDir.resolve("memory.db"))) {
            CoordinationStore.CoordinationTask foundation = store.createTask(
                    "Foundation", "Create schema", List.of(), List.of("src/schema/**"), "PM");
            CoordinationStore.CoordinationTask feature = store.createTask(
                    "Feature", "Use schema", List.of(foundation.id()), List.of("src/feature/**"), "PM");

            assertThatThrownBy(() -> store.claimTask(feature.id(), "Claude", 30, "feature", "worktrees/feature"))
                    .hasMessageContaining("dependencies are incomplete");

            CoordinationStore.CoordinationTask claimed = store.claimTask(
                    foundation.id(), "Codex", 30, "schema", "worktrees/schema").task();
            assertThat(claimed.status()).isEqualTo("IN_PROGRESS");
            assertThatThrownBy(() -> store.claimTask(foundation.id(), "Gemini", 30, "other", "other"))
                    .hasMessageContaining("leased by Codex");
            assertThatThrownBy(() -> store.heartbeatTask(foundation.id(), "Gemini", 30))
                    .hasMessageContaining("current task owner");

            CoordinationStore.CoordinationTask renewed = store.heartbeatTask(foundation.id(), "Codex", 45);
            CoordinationStore.HandoffResult completed = store.handoffTask(
                    foundation.id(), "Codex", renewed.version(), "COMPLETE", "session-1", "Schema complete",
                    List.of("src/schema/V2.sql"), List.of("migrationTest passed"), List.of(), "Claim feature",
                    "abc123");

            assertThat(completed.task().status()).isEqualTo("COMPLETE");
            assertThat(completed.task().owner()).isNull();
            assertThat(completed.handoff().filesChanged()).containsExactly("src/schema/V2.sql");
            assertThat(store.claimTask(feature.id(), "Claude", 30, "feature", "worktrees/feature").task().owner())
                    .isEqualTo("Claude");
            assertThat(store.snapshot(20, 20).handoffs()).extracting(CoordinationStore.Handoff::commitRef)
                    .contains("abc123");
        }
    }

    @Test
    void expectedFileOverlapWarnsWithoutPreventingParallelClaim(@TempDir Path tempDir) {
        try (CoordinationStore store = new CoordinationStore(tempDir.resolve("memory.db"))) {
            CoordinationStore.CoordinationTask first = store.createTask(
                    "First", "", List.of(), List.of("src/main/java/**"), "PM");
            CoordinationStore.CoordinationTask second = store.createTask(
                    "Second", "", List.of(), List.of("src/main/java/App.java"), "PM");
            store.claimTask(first.id(), "Claude", 30, "first", "wt/first");

            CoordinationStore.ClaimResult claim = store.claimTask(second.id(), "Gemini", 30, "second", "wt/second");

            assertThat(claim.task().owner()).isEqualTo("Gemini");
            assertThat(claim.warnings()).singleElement().asString()
                    .contains("FILE_OVERLAP", first.id(), "owner=Claude");
        }
    }

    @Test
    void expiredLeaseCanBeTakenOverAndIsReportedInSnapshot(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("memory.db");
        try (CoordinationStore store = new CoordinationStore(database)) {
            CoordinationStore.CoordinationTask task = store.createTask(
                    "Recoverable", "", List.of(), List.of("README.md"), "PM");
            store.claimTask(task.id(), "Claude", 30, "old", "wt/old");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 var statement = connection.prepareStatement(
                         "UPDATE coordination_tasks SET lease_expires_at=? WHERE id=?")) {
                statement.setString(1, Instant.now().minusSeconds(60).toString());
                statement.setString(2, task.id());
                statement.executeUpdate();
            }

            assertThat(store.snapshot(20, 20).warnings()).singleElement().asString()
                    .contains("STALE_LEASE", "previousOwner=Claude");
            assertThat(store.claimTask(task.id(), "Codex", 30, "new", "wt/new").task().owner())
                    .isEqualTo("Codex");
        }
    }

    @Test
    void concurrentProcessesCannotBothClaimOneLiveLease(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("memory.db");
        String taskId;
        try (CoordinationStore store = new CoordinationStore(database)) {
            taskId = store.createTask("Race", "", List.of(), List.of(), "PM").id();
        }
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2);
             CoordinationStore first = new CoordinationStore(database);
             CoordinationStore second = new CoordinationStore(database)) {
            var left = executor.submit(() -> claimAfter(start, first, taskId, "Claude"));
            var right = executor.submit(() -> claimAfter(start, second, taskId, "Codex"));
            start.countDown();

            assertThat(List.of(left.get(), right.get())).containsExactlyInAnyOrder(true, false);
        }
    }

    private static boolean claimAfter(CountDownLatch start, CoordinationStore store, String taskId, String owner)
            throws InterruptedException {
        start.await();
        try {
            store.claimTask(taskId, owner, 30, "", "");
            return true;
        } catch (IllegalStateException expectedConflict) {
            return false;
        }
    }
}
