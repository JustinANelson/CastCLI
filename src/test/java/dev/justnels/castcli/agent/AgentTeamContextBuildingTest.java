package dev.justnels.castcli.agent;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.RoutingConfig;
import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies {@link AgentTeam#buildContext} bounds prior-subtask context instead of growing without
 * bound: recent outputs stay in full, older ones collapse to a one-line summary. */
class AgentTeamContextBuildingTest {
    private static final HarnessConfig CONFIG = new HarnessConfig(
            List.of(new ProviderConfig("small", ModelTier.SMALL_LOCAL, "http://localhost/v1/", "small-model", null, 0.1, 30, true, true)),
            new RoutingConfig(240, true, 180, 1),
            new ToolConfig(".", 100_000, false));

    private final AgentTeam team = new AgentTeam(CONFIG, new HarnessOrchestrator(CONFIG) {
        @Override
        public Outcome run(TaskRequest task) {
            throw new UnsupportedOperationException("buildContext must not call the orchestrator");
        }
    });

    @Test
    void mostRecentOutputStaysFullWhileOlderLongOutputIsSummarized() {
        String oldestOutputPrefix = "OLDEST-OUTPUT-START ";
        String oldestOutput = oldestOutputPrefix + "x".repeat(400) + " OLDEST-OUTPUT-END";
        SubTask oldest = new SubTask(1, "Old Subtask", AgentRole.CODER, "prompt", "COMPLETED", oldestOutput);
        SubTask newest = new SubTask(2, "New Subtask", AgentRole.CODER, "prompt", "COMPLETED", "Newest output body.");

        String context = team.buildContext(List.of(oldest, newest));

        assertThat(context).contains("Newest output body.");
        assertThat(context).contains(oldestOutputPrefix); // summary keeps the leading prefix
        assertThat(context).doesNotContain("OLDEST-OUTPUT-END"); // but the tail is cut off by summarization
        assertThat(context).doesNotContain(oldestOutput); // the full 400-char body never appears verbatim
    }

    @Test
    void emptyCompletedListYieldsEmptyContext() {
        assertThat(team.buildContext(List.of())).isEmpty();
    }
}

