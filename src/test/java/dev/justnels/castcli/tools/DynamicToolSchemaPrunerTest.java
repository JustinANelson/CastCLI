package dev.justnels.castcli.tools;

import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicToolSchemaPrunerTest {

    private final ToolConfig config = new ToolConfig(".", 262144, false);
    private final DefaultToolSelector selector = new DefaultToolSelector();

    @Test
    void prunesAllToolsForGeneralQueries() {
        TaskRequest request = new TaskRequest("Explain standard deviation in statistics", Workload.QUICK, ModelTier.SMALL_LOCAL);
        List<Object> tools = selector.selectTools(request, config, DenyApprovalGate.INSTANCE);

        assertThat(tools).isEmpty();
    }

    @Test
    void includesOnlyWorkspaceToolsForCodeRequests() {
        TaskRequest request = new TaskRequest("read file src/Main.java", Workload.CODE, ModelTier.SMALL_LOCAL);
        List<Object> tools = selector.selectTools(request, config, DenyApprovalGate.INSTANCE);

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0)).isInstanceOf(WorkspaceTools.class);
    }
}
