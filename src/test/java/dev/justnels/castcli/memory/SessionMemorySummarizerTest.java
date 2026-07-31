package dev.justnels.castcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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

            List<MemoryEntry> recalled = summarizer.recallSessionMemory("Session Turnover", 10);
            assertThat(recalled).hasSize(1);
            assertThat(recalled.get(0).id()).isEqualTo(entry.id());
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
