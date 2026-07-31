package dev.justnels.castcli.routing;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalPreFlightClassifierTest {

    private HarnessConfig mockConfig() {
        ProviderConfig small = new ProviderConfig("small", ModelTier.SMALL_LOCAL, "http://fake/v1/", "model", null, 0.0, 10, true, true);
        ProviderConfig large = new ProviderConfig("large", ModelTier.LARGE_LOCAL, "http://fake/v1/", "model", null, 0.0, 10, true, true);
        ProviderConfig cloud = new ProviderConfig("cloud", ModelTier.FRONTIER_CLOUD, "http://fake/v1/", "model", "KEY", 1.0, 10, true, true);
        return new HarnessConfig(List.of(small, large, cloud), null, null);
    }

    @Test
    void classifiesQuickTasksToSmallLocal() {
        LocalPreFlightClassifier classifier = new LocalPreFlightClassifier(mockConfig());
        TaskRequest request = new TaskRequest("Format this JSON snippet", Workload.QUICK, null);
        assertThat(classifier.classifyTier(request)).isEqualTo(ModelTier.SMALL_LOCAL);
    }

    @Test
    void classifiesCodeTasksToLargeLocal() {
        LocalPreFlightClassifier classifier = new LocalPreFlightClassifier(mockConfig());
        TaskRequest request = new TaskRequest("Refactor Java class", Workload.CODE, null);
        assertThat(classifier.classifyTier(request)).isEqualTo(ModelTier.LARGE_LOCAL);
    }

    @Test
    void classifiesFrontierReasoningToCloud() {
        LocalPreFlightClassifier classifier = new LocalPreFlightClassifier(mockConfig());
        TaskRequest request = new TaskRequest("Perform architectural design and security audit", Workload.AUTO, null);
        assertThat(classifier.classifyTier(request)).isEqualTo(ModelTier.FRONTIER_CLOUD);
    }
}
