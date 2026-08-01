package dev.justnels.castcli.mcp;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.RoutingConfig;
import dev.justnels.castcli.config.ToolConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpUsageSummaryTest {
    @Test
    void aggregatesDelegationsAndEstimatesFrontierEquivalentSavings() {
        ProviderConfig local = new ProviderConfig("local", ModelTier.SMALL_LOCAL, "http://local/v1/",
                "local-model", null, 0, 30, true, true, null, 0.10, 0.20);
        ProviderConfig frontier = new ProviderConfig("frontier", ModelTier.FRONTIER_CLOUD, "http://cloud/v1/",
                "frontier-model", null, 0, 30, true, true, null, 10.0, 20.0);
        HarnessConfig config = new HarnessConfig(List.of(local, frontier), new RoutingConfig(240, true),
                new ToolConfig(".", 100_000, false));
        List<McpUsageRecord> records = List.of(
                new McpUsageRecord(1, "a", "trace-a", "ask_local", true, 200,
                        "local", "SMALL_LOCAL", "local-model", 1_000, 500, 0.0002,
                        "hash", 100, 200, null),
                new McpUsageRecord(2, "b", "trace-b", "read_workspace_file", true, 10,
                        null, null, null, 0, 0, 0, null, 0, 20, null),
                new McpUsageRecord(3, "c", "trace-c", "ask_local", false, 30,
                        null, null, null, 0, 0, 0, null, 40, 10, "timeout"),
                new McpUsageRecord(4, "d", "trace-d", "draft_patch", true, 100,
                        "local", "SMALL_LOCAL", "local-model", 200, 100, 0.00005,
                        "hash-2", 80, 120, null));

        McpUsageSummary summary = McpUsageSummary.summarize(records, config);

        assertThat(summary.totalCalls()).isEqualTo(4);
        assertThat(summary.askLocalCalls()).isEqualTo(2);
        assertThat(summary.delegationCalls()).isEqualTo(3);
        assertThat(summary.successfulDelegations()).isEqualTo(2);
        assertThat(summary.localTotalTokens()).isEqualTo(1_800);
        assertThat(summary.estimatedFrontierEquivalentCostUsd()).isEqualTo(0.024);
        assertThat(summary.estimatedCostAvoidedUsd()).isEqualTo(0.02375);
        assertThat(summary.callsByTool()).containsEntry("ask_local", 2);
        assertThat(summary.performanceByTool().get("ask_local"))
                .isEqualTo(new McpUsageSummary.ToolPerformance(2, 1, 1, 0, 1, 30, 200, 105));
        assertThat(summary.usageByProvider().get("local").calls()).isEqualTo(2);
        assertThat(summary.callerModels()).isEmpty(); // no callerModel on these records
    }

    @Test
    void callerModelOnRecordDrivesFrontierPricingFromMatchingProvider() {
        // The caller configured in the harness with known rates
        ProviderConfig local = new ProviderConfig("local", ModelTier.SMALL_LOCAL, "http://local/v1/",
                "local-model", null, 0, 30, true, true, null, 0.10, 0.20);
        ProviderConfig callerProvider = new ProviderConfig("claude", ModelTier.FRONTIER_CLOUD, "http://cloud/v1/",
                "claude-sonnet-4-6", null, 0, 30, true, true, null, 3.0, 15.0);
        HarnessConfig config = new HarnessConfig(List.of(local, callerProvider), new RoutingConfig(240, true),
                new ToolConfig(".", 100_000, false));

        // Record with callerModel set — 1000 input + 500 output tokens
        // frontier cost = (1000/1e6)*3.0 + (500/1e6)*15.0 = 0.000003 + 0.0000075 = 0.0000105
        List<McpUsageRecord> records = List.of(
                new McpUsageRecord(1, "a", "trace-a", "ask_local", true, 200,
                        "local", "SMALL_LOCAL", "local-model", 1_000, 500, 0.0002,
                        "hash", 100, 200, null, "claude-sonnet-4-6"));

        McpUsageSummary summary = McpUsageSummary.summarize(records, config);

        assertThat(summary.successfulDelegations()).isEqualTo(1);
        assertThat(summary.estimatedFrontierEquivalentCostUsd()).isCloseTo(0.0105, org.assertj.core.api.Assertions.within(1e-10));
        assertThat(summary.callerModels()).containsExactly("claude-sonnet-4-6");
    }
}
