package dev.justnels.castcli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.RoutingConfig;
import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.observability.CastTelemetry;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.tools.WorkspaceTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlyDelegationToolsTest {
    @TempDir
    Path workspace;

    @Test
    void cancelsDelegationAtTheToolLevelDeadline() throws Exception {
        ProviderConfig provider = new ProviderConfig("small", ModelTier.SMALL_LOCAL, "http://fake/v1/",
                "small-model", null, 0.1, 30, true, true);
        HarnessConfig config = new HarnessConfig(List.of(provider), new RoutingConfig(240, true),
                new ToolConfig(workspace.toString(), 100_000, false));
        HarnessOrchestrator slowOrchestrator = new HarnessOrchestrator(config) {
            @Override public Outcome run(TaskRequest task) {
                try {
                    Thread.sleep(5_000);
                    throw new AssertionError("deadline did not cancel the delegation");
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cancelled", expected);
                }
            }
        };
        ReadOnlyDelegationTools tools = new ReadOnlyDelegationTools(
                slowOrchestrator, new WorkspaceTools(workspace, 100_000),
                CastTelemetry.initialize(config.observability(), workspace), 12_000, 25,
                ModelTier.SMALL_LOCAL, ModelTier.SMALL_LOCAL);
        var arguments = new ObjectMapper().createObjectNode()
                .put("diff", "diff --git a/A.java b/A.java");

        assertThatThrownBy(() -> tools.reviewDiff(arguments))
                .hasMessageContaining("25 ms deadline");
    }
}
