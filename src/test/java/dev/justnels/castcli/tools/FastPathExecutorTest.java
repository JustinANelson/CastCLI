package dev.justnels.castcli.tools;

import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FastPathExecutorTest {
    private final FastPathExecutor executor = new FastPathExecutor();
    private final ToolConfig config = new ToolConfig(".", 100000, false);

    @Test
    void executesTimeQueryFastPath() {
        TaskRequest task = new TaskRequest("what time is it", Workload.AUTO, null);
        Optional<FastPathExecutor.FastPathResult> result = executor.executeIfPossible(task, config);

        assertThat(result).isPresent();
        assertThat(result.get().toolUsed()).isEqualTo("currentTime");
        assertThat(result.get().answer()).contains("Current UTC time is");
    }

    @Test
    void executesFileListFastPath() {
        TaskRequest task = new TaskRequest("list workspace files matching *.md", Workload.AUTO, null);
        Optional<FastPathExecutor.FastPathResult> result = executor.executeIfPossible(task, config);

        assertThat(result).isPresent();
        assertThat(result.get().toolUsed()).isEqualTo("listWorkspaceFiles");
        assertThat(result.get().answer()).contains("README.md");
    }

    @Test
    void returnsEmptyForNonFastPathTasks() {
        TaskRequest task = new TaskRequest("Write a quick sorting algorithm in Java", Workload.AUTO, null);
        Optional<FastPathExecutor.FastPathResult> result = executor.executeIfPossible(task, config);

        assertThat(result).isEmpty();
    }
}

