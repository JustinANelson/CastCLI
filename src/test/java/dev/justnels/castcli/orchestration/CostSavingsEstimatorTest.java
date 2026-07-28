package dev.justnels.castcli.orchestration;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.RoutingConfig;
import dev.justnels.castcli.config.ToolConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CostSavingsEstimatorTest {
    private static final ProviderConfig SMALL = new ProviderConfig(
            "small", ModelTier.SMALL_LOCAL, "http://fake/v1/", "small-model", null, 0.1, 30, true, true, null, 0.0, 0.0);
    private static final ProviderConfig CLOUD = new ProviderConfig(
            "cloud", ModelTier.FRONTIER_CLOUD, "http://fake/v1/", "gpt-4o", null, 0.1, 30, true, true, null, 5.0, 15.0);

    @Test
    void estimatesCostAvoidedForOffloadedCallsUsingReferenceFrontierRates() {
        HarnessConfig config = new HarnessConfig(
                List.of(SMALL, CLOUD), new RoutingConfig(240, true), new ToolConfig(".", 100_000, false));
        CostSavingsEstimator estimator = new CostSavingsEstimator(config);

        assertThat(estimator.hasReference()).isTrue();
        assertThat(estimator.referenceProvider()).contains(CLOUD);
        assertThat(estimator.isOffloaded(SMALL)).isTrue();
        assertThat(estimator.isOffloaded(CLOUD)).isFalse();

        // 1,000,000 input tokens + 1,000,000 output tokens at $5/$15 per million => $20.
        double avoided = estimator.estimateAvoidedCostUsd(SMALL, 1_000_000, 1_000_000);
        assertThat(avoided).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void frontierCallsThemselvesAvoidNothing() {
        HarnessConfig config = new HarnessConfig(
                List.of(SMALL, CLOUD), new RoutingConfig(240, true), new ToolConfig(".", 100_000, false));
        CostSavingsEstimator estimator = new CostSavingsEstimator(config);

        assertThat(estimator.estimateAvoidedCostUsd(CLOUD, 1_000_000, 1_000_000)).isZero();
    }

    @Test
    void withoutAnyEnabledFrontierProviderEverySavingsEstimateIsZero() {
        HarnessConfig config = new HarnessConfig(
                List.of(SMALL), new RoutingConfig(240, true), new ToolConfig(".", 100_000, false));
        CostSavingsEstimator estimator = new CostSavingsEstimator(config);

        assertThat(estimator.hasReference()).isFalse();
        assertThat(estimator.referenceProvider()).isEmpty();
        assertThat(estimator.estimateAvoidedCostUsd(SMALL, 1_000_000, 1_000_000)).isZero();
    }

    @Test
    void disabledFrontierProviderIsNotUsedAsReference() {
        ProviderConfig disabledCloud = new ProviderConfig(
                "cloud-disabled", ModelTier.FRONTIER_CLOUD, "http://fake/v1/", "gpt-4o", null, 0.1, 30, true, false, null, 5.0, 15.0);
        HarnessConfig config = new HarnessConfig(
                List.of(SMALL, disabledCloud), new RoutingConfig(240, true), new ToolConfig(".", 100_000, false));
        CostSavingsEstimator estimator = new CostSavingsEstimator(config);

        assertThat(estimator.hasReference()).isFalse();
    }

    @Test
    void callerModelMatchingConfiguredProviderUsesItsRates() {
        // A provider configured with the same model name as the caller — rates differ from CLOUD.
        ProviderConfig callerProvider = new ProviderConfig(
                "claude", ModelTier.FRONTIER_CLOUD, "http://api/v1/", "claude-sonnet-4-6", null, 0, 30, true, true, null, 3.0, 15.0);
        HarnessConfig config = new HarnessConfig(
                List.of(SMALL, callerProvider), new RoutingConfig(240, true), new ToolConfig(".", 100_000, false));
        CostSavingsEstimator estimator = new CostSavingsEstimator(config);

        // 1M input @ $3 + 1M output @ $15 = $18
        double avoided = estimator.estimateAvoidedCostUsd(SMALL, 1_000_000, 1_000_000, "claude-sonnet-4-6");
        assertThat(avoided).isCloseTo(18.0, within(1e-9));
    }

    @Test
    void callerModelMatchIsCaseInsensitive() {
        ProviderConfig callerProvider = new ProviderConfig(
                "claude", ModelTier.FRONTIER_CLOUD, "http://api/v1/", "Claude-Sonnet-4-6", null, 0, 30, true, true, null, 3.0, 15.0);
        HarnessConfig config = new HarnessConfig(
                List.of(SMALL, callerProvider), new RoutingConfig(240, true), new ToolConfig(".", 100_000, false));
        CostSavingsEstimator estimator = new CostSavingsEstimator(config);

        // Mixed-case callerModel string should still match
        double avoided = estimator.estimateAvoidedCostUsd(SMALL, 1_000_000, 1_000_000, "claude-sonnet-4-6");
        assertThat(avoided).isCloseTo(18.0, within(1e-9));
    }

    @Test
    void callerModelWithNoMatchFallsBackToFrontierCloudReference() {
        HarnessConfig config = new HarnessConfig(
                List.of(SMALL, CLOUD), new RoutingConfig(240, true), new ToolConfig(".", 100_000, false));
        CostSavingsEstimator estimator = new CostSavingsEstimator(config);

        // "unknown-model" won't match anything; falls back to CLOUD ($5/$15 per million)
        double avoided = estimator.estimateAvoidedCostUsd(SMALL, 1_000_000, 1_000_000, "unknown-model");
        assertThat(avoided).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void nullCallerModelBehavesLikeOriginalOverload() {
        HarnessConfig config = new HarnessConfig(
                List.of(SMALL, CLOUD), new RoutingConfig(240, true), new ToolConfig(".", 100_000, false));
        CostSavingsEstimator estimator = new CostSavingsEstimator(config);

        double withNull = estimator.estimateAvoidedCostUsd(SMALL, 1_000_000, 1_000_000, null);
        double original = estimator.estimateAvoidedCostUsd(SMALL, 1_000_000, 1_000_000);
        assertThat(withNull).isCloseTo(original, within(1e-9));
    }
}
