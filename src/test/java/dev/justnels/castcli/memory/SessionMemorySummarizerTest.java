package dev.justnels.castcli.memory;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.RoutingConfig;
import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SessionMemorySummarizerTest {

    @TempDir Path tempDir;

    @Test
    void summarizesSessionActionsAndPersistsToLongTermMemory() throws Exception {
        SqliteMemoryStore store = new SqliteMemoryStore(tempDir.resolve("memory.db"));
        try (SessionMemorySummarizer summarizer = new SessionMemorySummarizer(store, null, "session")) {
            List<SessionAction> actions = List.of(
                    new SessionAction("sess-101", "PM", "Plan Created", "Decomposed refactoring into 3 subtasks"),
                    new SessionAction("sess-101", "Coder", "Code Refactored", "Updated SessionMemorySummarizer for turnover"),
                    new SessionAction("sess-101", "Reviewer", "Code Reviewed", "VERDICT: APPROVED")
            );

            MemoryEntry entry = summarizer.summarizeSession("sess-101", "PM", actions);

            assertThat(entry).isNotNull();
            assertThat(entry.topic()).isEqualTo("session-summary:sess-101");
            assertThat(entry.namespace()).isEqualTo("session");
            assertThat(entry.scope()).isEqualTo("session-turnover");
            assertThat(entry.content()).contains("Session Turnover Summary");
            assertThat(entry.content()).contains("Updated SessionMemorySummarizer");
            // No orchestrator available, so the stored content is the deterministic extractive log
            // itself -- no LLM step ran, so it's fully trustworthy.
            assertThat(entry.confidence()).isEqualTo(0.95);

            List<MemoryEntry> recalled = summarizer.recallSessionMemory("Session Turnover", 10);
            assertThat(recalled).hasSize(1);
            assertThat(recalled.get(0).id()).isEqualTo(entry.id());
        }
    }

    @Test
    void condensationIsGroundedInTheExtractiveLogAndRoutedToTheLargerLocalTier() {
        AtomicReference<TaskRequest> capturedRequest = new AtomicReference<>();
        HarnessConfig config = new HarnessConfig(
                List.of(new ProviderConfig("large", ModelTier.LARGE_LOCAL, "http://fake/v1/", "large-model", null,
                        0.1, 30, true, true)),
                new RoutingConfig(240, true), new ToolConfig(".", 100_000, false));
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(config) {
            @Override
            public Outcome run(TaskRequest task) {
                capturedRequest.set(task);
                return new Outcome(config.providers().get(0), "Condensed report text", List.of());
            }
        };
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        try (SessionMemorySummarizer summarizer = new SessionMemorySummarizer(store, orchestrator, "session")) {
            List<SessionAction> actions = List.of(new SessionAction("sess-303", "Coder", "Refactor", "Renamed a method"));

            MemoryEntry entry = summarizer.summarizeSession("sess-303", "Coder", actions);

            assertThat(entry.content()).isEqualTo("Condensed report text");
            // Model-condensed content is stored with lower confidence than the deterministic extractive
            // log, so downstream readers know to verify it (see MemoryContextProvider).
            assertThat(entry.confidence()).isEqualTo(0.75);

            TaskRequest sent = capturedRequest.get();
            assertThat(sent.requestedTier()).isEqualTo(ModelTier.LARGE_LOCAL);
            assertThat(sent.toolsDisabled()).isTrue();
            assertThat(sent.prompt())
                    .contains("VERBATIM LOG (ground truth -- do not contradict or add to this):")
                    .contains("Renamed a method")
                    .contains("Never infer, assume, guess");
        }
    }

    @Test
    void asyncSummarizationCompletesSuccessfully() throws Exception {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        try (SessionMemorySummarizer summarizer = new SessionMemorySummarizer(store, null, "session")) {
            List<SessionAction> actions = List.of(
                    new SessionAction("sess-202", "Coder", "Test Created", "Added unit tests")
            );

            CompletableFuture<MemoryEntry> future = summarizer.summarizeSessionAsync("sess-202", "Coder", actions);
            MemoryEntry entry = future.get();

            assertThat(entry.topic()).isEqualTo("session-summary:sess-202");
            assertThat(store.list("session", 10)).hasSize(1);
        }
    }

    @Test
    void handlesEmptyActionsGracefully() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        try (SessionMemorySummarizer summarizer = new SessionMemorySummarizer(store, null, "session")) {
            MemoryEntry entry = summarizer.summarizeSession("sess-empty", "Agent", List.of());

            assertThat(entry).isNotNull();
            assertThat(entry.content()).contains("No actions recorded");
        }
    }
}
