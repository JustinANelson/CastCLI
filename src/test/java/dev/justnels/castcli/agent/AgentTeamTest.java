package dev.justnels.castcli.agent;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTeamTest {
    @TempDir
    Path checkpointDir;

    private final HarnessConfig config = new HarnessConfig(
            List.of(
                    new ProviderConfig("small", ModelTier.SMALL_LOCAL, "http://localhost/v1/", "small-model", null, 0.1, 30, true, true),
                    new ProviderConfig("large", ModelTier.LARGE_LOCAL, "http://localhost/v1/", "large-model", null, 0.1, 30, true, true),
                    new ProviderConfig("cloud", ModelTier.FRONTIER_CLOUD, "http://localhost/v1/", "cloud-model", null, 0.1, 30, true, true)
            ),
            new RoutingConfig(240, true),
            new ToolConfig(".", 100000, false)
    );

    static class TestOrchestrator extends HarnessOrchestrator {
        public TestOrchestrator(HarnessConfig config) {
            super(config);
        }

        @Override
        public Outcome run(TaskRequest task) {
            if (task.prompt().contains("Break down the following goal")) {
                String pmPlan = """
                        TITLE: Implement cache storage
                        ROLE: CODER
                        PROMPT: Create ConcurrentHashMap cache storage.

                        TITLE: Review implementation
                        ROLE: REVIEWER
                        PROMPT: Audit cache storage code for concurrency bugs.
                        """;
                return new Outcome(FAST_PATH_PROVIDER, pmPlan, List.of(), List.of(), 10L, false);
            }
            if (task.prompt().contains("Commissioning Agent")) {
                return new Outcome(FAST_PATH_PROVIDER, "Commissioning Approved: Implementation and Review complete.", List.of(), List.of(), 10L, false);
            }
            return new Outcome(FAST_PATH_PROVIDER, "Worker deliverable executed successfully.", List.of(), List.of(), 5L, false);
        }
    }

    @Test
    void commissionsProjectGoalSuccessfully() {
        TestOrchestrator orchestrator = new TestOrchestrator(config);
        AgentTeam team = new AgentTeam(config, orchestrator, new CheckpointStore(checkpointDir));

        CommissioningResult result = team.commission("Create a thread-safe LRU Cache in Java");

        assertThat(result.plan().subtasks()).hasSize(2);
        assertThat(result.completedTasks()).hasSize(2);
        assertThat(result.completedTasks().get(0).assignedRole()).isEqualTo(AgentRole.CODER);
        assertThat(result.completedTasks().get(1).assignedRole()).isEqualTo(AgentRole.REVIEWER);
        assertThat(result.commissioningSummary()).contains("Commissioning Approved");
    }

    @Test
    void refusesToCompleteWorkerWithNoFinalAnswer() {
        TestOrchestrator orchestrator = new TestOrchestrator(config) {
            @Override public Outcome run(TaskRequest task) {
                if (task.prompt().contains("Break down the following goal")
                        || task.prompt().contains("Commissioning Agent")) {
                    return super.run(task);
                }
                return new Outcome(FAST_PATH_PROVIDER, null, List.of("WorkspaceTools"),
                        List.of("writeWorkspaceFile"), 5L, false);
            }
        };
        AgentTeam team = new AgentTeam(config, orchestrator, new CheckpointStore(checkpointDir));

        assertThatThrownBy(() -> team.commission("Create files"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ended without a final answer")
                .hasMessageContaining("cannot be marked complete");
    }
}

