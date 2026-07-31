package dev.justnels.castcli.tools;

import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FastPathExecutorTest {

    private final ToolConfig config = new ToolConfig(".", 262144, false);
    private final FastPathExecutor executor = new FastPathExecutor();

    @Test
    void executesTimeQueryFastPath() {
        TaskRequest request = new TaskRequest("what time is it in UTC?", Workload.QUICK, ModelTier.SMALL_LOCAL);
        Optional<FastPathExecutor.FastPathResult> result = executor.executeIfPossible(request, config);

        assertThat(result).isPresent();
        assertThat(result.get().toolUsed()).isEqualTo("currentTime");
        assertThat(result.get().answer()).contains("Current UTC time");
    }

    @Test
    void executesDateQueryFastPath() {
        TaskRequest request = new TaskRequest("what day is it?", Workload.QUICK, ModelTier.SMALL_LOCAL);
        Optional<FastPathExecutor.FastPathResult> result = executor.executeIfPossible(request, config);

        assertThat(result).isPresent();
        assertThat(result.get().toolUsed()).isEqualTo("currentDate");
        assertThat(result.get().answer()).contains("Current UTC date");
    }

    @Test
    void executesVersionQueryFastPath() {
        TaskRequest request = new TaskRequest("cast-cli version", Workload.QUICK, ModelTier.SMALL_LOCAL);
        Optional<FastPathExecutor.FastPathResult> result = executor.executeIfPossible(request, config);

        assertThat(result).isPresent();
        assertThat(result.get().toolUsed()).isEqualTo("versionInfo");
    }
}
