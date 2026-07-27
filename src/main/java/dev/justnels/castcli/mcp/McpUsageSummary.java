package dev.justnels.castcli.mcp;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.orchestration.CostSavingsEstimator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Aggregated, immutable view of MCP utilization and local-delegation efficiency. */
public record McpUsageSummary(
        int totalCalls,
        int successfulCalls,
        int askLocalCalls,
        int delegationCalls,
        int successfulDelegations,
        long localInputTokens,
        long localOutputTokens,
        double localEstimatedCostUsd,
        double estimatedFrontierEquivalentCostUsd,
        double estimatedCostAvoidedUsd,
        long averageDelegationDurationMs,
        Map<String, Integer> callsByTool,
        Map<String, ProviderUsage> usageByProvider) {

    public record ProviderUsage(int calls, long inputTokens, long outputTokens, double estimatedCostUsd) {
        public long totalTokens() { return inputTokens + outputTokens; }
    }

    public static McpUsageSummary summarize(List<McpUsageRecord> records, HarnessConfig config) {
        int successful = 0;
        int askCalls = 0;
        int delegations = 0;
        int delegationCalls = 0;
        long input = 0;
        long output = 0;
        long duration = 0;
        double localCost = 0;
        double frontierEquivalentCost = 0;
        Map<String, Integer> tools = new TreeMap<>();
        Map<String, MutableProviderUsage> providers = new TreeMap<>();
        Map<String, ProviderConfig> providerConfigs = new LinkedHashMap<>();
        config.providers().forEach(provider -> providerConfigs.put(provider.id(), provider));
        CostSavingsEstimator estimator = new CostSavingsEstimator(config);

        for (McpUsageRecord record : records) {
            tools.merge(record.toolName(), 1, Integer::sum);
            if (record.success()) successful++;
            if ("ask_local".equals(record.toolName())) askCalls++;
            if (record.delegationAttempted()) delegationCalls++;
            if (!record.delegated()) continue;
            delegations++;
            input += record.inputTokens();
            output += record.outputTokens();
            duration += record.durationMs();
            localCost += record.estimatedCostUsd();
            ProviderConfig provider = providerConfigs.get(record.providerId());
            if (provider != null) {
                frontierEquivalentCost += estimator.estimateAvoidedCostUsd(
                        provider, record.inputTokens(), record.outputTokens());
            }
            providers.computeIfAbsent(record.providerId(), ignored -> new MutableProviderUsage())
                    .add(record);
        }

        Map<String, ProviderUsage> immutableProviders = new LinkedHashMap<>();
        providers.forEach((id, usage) -> immutableProviders.put(id, usage.freeze()));
        return new McpUsageSummary(records.size(), successful, askCalls, delegationCalls, delegations, input, output, localCost,
                frontierEquivalentCost, Math.max(0, frontierEquivalentCost - localCost),
                delegations == 0 ? 0 : duration / delegations, Map.copyOf(tools), Map.copyOf(immutableProviders));
    }

    public long localTotalTokens() {
        return localInputTokens + localOutputTokens;
    }

    public double successfulDelegationRate() {
        return delegationCalls == 0 ? 0 : (double) successfulDelegations / delegationCalls;
    }

    private static final class MutableProviderUsage {
        int calls;
        long input;
        long output;
        double cost;
        void add(McpUsageRecord record) {
            calls++;
            input += record.inputTokens();
            output += record.outputTokens();
            cost += record.estimatedCostUsd();
        }
        ProviderUsage freeze() {
            return new ProviderUsage(calls, input, output, cost);
        }
    }
}
