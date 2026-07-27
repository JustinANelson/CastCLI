package dev.justnels.castcli.tools;

import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultToolSelectorTest {
    private final DefaultToolSelector selector = new DefaultToolSelector();
    private final ToolConfig toolConfig = new ToolConfig(".", 1000, true);

    @Test
    void selectsSystemToolsForTimeQuery() {
        TaskRequest task = new TaskRequest("what time is it in UTC?", Workload.AUTO, null);
        List<Object> tools = selector.selectTools(task, toolConfig);

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0)).isInstanceOf(SystemTools.class);
    }

    @Test
    void selectsWorkspaceToolsForFileSearch() {
        TaskRequest task = new TaskRequest("find file named README.md", Workload.AUTO, null);
        List<Object> tools = selector.selectTools(task, toolConfig);

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0)).isInstanceOf(WorkspaceTools.class);
    }

    @Test
    void selectsJavaShellWhenEnabledAndRequested() {
        TaskRequest task = new TaskRequest("calculate evaluate java Math.pow(2, 10)", Workload.AUTO, null);
        List<Object> tools = selector.selectTools(task, toolConfig);

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0)).isInstanceOf(JavaShellTool.class);
    }

    @Test
    void selectsNoToolsForPureTextPrompt() {
        TaskRequest task = new TaskRequest("Explain what a record in Java is", Workload.QUICK, null);
        List<Object> tools = selector.selectTools(task, toolConfig);

        assertThat(tools).isEmpty();
    }

    @Test
    void selectsNoToolsForArchitecturalComparisonPrompt() {
        TaskRequest task = new TaskRequest("Compare running CastCLI in Docker vs linking as a Gradle dependency in a project", Workload.AUTO, null);
        List<Object> tools = selector.selectTools(task, toolConfig);

        assertThat(tools).isEmpty();
    }
}

