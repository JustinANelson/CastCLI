package dev.justnels.castcli.mcp;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.orchestration.CostSavingsEstimator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpCallerModelAutoDetectorTest {

    @Test
    void resolvesReferenceFrontierProviderEvenWhenUnconfigured() {
        ProviderConfig local = new ProviderConfig("small-local", ModelTier.SMALL_LOCAL, "http://fake/v1/", "model", null, 0.1, 30, true, true, 10, 0.0, 0.0);
        ProviderConfig cloud = new ProviderConfig("frontier-cloud", ModelTier.FRONTIER_CLOUD, "https://api.openai.com/v1/", "gpt-4o", "KEY", 0.1, 30, true, false, 10, 3.0, 15.0);

        HarnessConfig config = new HarnessConfig(List.of(local, cloud), null, null);
        CostSavingsEstimator estimator = new CostSavingsEstimator(config);

        assertThat(estimator.resolveCallerProvider("gpt-4o")).isPresent();
        double avoided = estimator.estimateAvoidedCostUsd(local, 1000, 500, "gpt-4o");
        assertThat(avoided).isGreaterThan(0.0);
    }
}
