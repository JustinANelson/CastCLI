package dev.justnels.castcli.mcp;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.orchestration.CostSavingsEstimator;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
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
        long totalPromptChars,
        long totalResultChars,
        Map<String, Integer> callsByTool,
        Map<String, ToolPerformance> performanceByTool,
        Map<String, ProviderUsage> usageByProvider,
        List<String> callerModels) {

    public McpUsageSummary(
            int totalCalls, int successfulCalls, int askLocalCalls, int delegationCalls,
            int successfulDelegations, long localInputTokens, long localOutputTokens,
            double localEstimatedCostUsd, double estimatedFrontierEquivalentCostUsd,
            double estimatedCostAvoidedUsd, long averageDelegationDurationMs,
            Map<String, Integer> callsByTool, Map<String, ToolPerformance> performanceByTool,
            Map<String, ProviderUsage> usageByProvider, List<String> callerModels) {
        this(totalCalls, successfulCalls, askLocalCalls, delegationCalls, successfulDelegations,
                localInputTokens, localOutputTokens, localEstimatedCostUsd,
                estimatedFrontierEquivalentCostUsd, estimatedCostAvoidedUsd,
                averageDelegationDurationMs, 0, 0, callsByTool, performanceByTool,
                usageByProvider, callerModels);
    }

    public long mcpPayloadInputTokens() {
        return totalPromptChars / 4;
    }

    public long mcpPayloadOutputTokens() {
        return totalResultChars / 4;
    }

    public long mcpPayloadTotalTokens() {
        return (totalPromptChars + totalResultChars) / 4;
    }

    public record ProviderUsage(int calls, long inputTokens, long outputTokens, double estimatedCostUsd) {
        public long totalTokens() { return inputTokens + outputTokens; }
    }

    public record ToolPerformance(int calls, int successes, int timeouts, int contextRejections,
                                  int fallbacks, long p50DurationMs, long p95DurationMs,
                                  long avgResultChars) {
        public ToolPerformance(int calls, int successes, int timeouts, int contextRejections,
                               int fallbacks, long p50DurationMs, long p95DurationMs) {
            this(calls, successes, timeouts, contextRejections, fallbacks, p50DurationMs, p95DurationMs, 0);
        }

        public long avgPayloadTokens() {
            return avgResultChars / 4;
        }
    }

    public static McpUsageSummary summarize(List<McpUsageRecord> records, HarnessConfig config) {
        int successful = 0;
        int askCalls = 0;
        int delegations = 0;
        int delegationCalls = 0;
        long input = 0;
        long output = 0;
        long duration = 0;
        long totalPromptChars = 0;
        long totalResultChars = 0;
        double localCost = 0;
        double frontierEquivalentCost = 0;
        Map<String, Integer> tools = new TreeMap<>();
        Map<String, MutableToolPerformance> toolPerformance = new TreeMap<>();
        Map<String, MutableProviderUsage> providers = new TreeMap<>();
        Map<String, ProviderConfig> providerConfigs = new LinkedHashMap<>();
        config.providers().forEach(provider -> providerConfigs.put(provider.id(), provider));
        CostSavingsEstimator estimator = new CostSavingsEstimator(config);
        SequencedSet<String> callerModelsSeen = new LinkedHashSet<>();

        for (McpUsageRecord record : records) {
            tools.merge(record.toolName(), 1, Integer::sum);
            toolPerformance.computeIfAbsent(record.toolName(), ignored -> new MutableToolPerformance())
                    .add(record);
            totalPromptChars += record.promptChars();
            totalResultChars += record.resultChars();
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
                        provider, record.inputTokens(), record.outputTokens(), record.callerModel());
            }
            if (record.callerModel() != null) callerModelsSeen.add(record.callerModel());
            providers.computeIfAbsent(record.providerId(), ignored -> new MutableProviderUsage())
                    .add(record);
        }

        Map<String, ProviderUsage> immutableProviders = new LinkedHashMap<>();
        providers.forEach((id, usage) -> immutableProviders.put(id, usage.freeze()));
        Map<String, ToolPerformance> immutableToolPerformance = new LinkedHashMap<>();
        toolPerformance.forEach((name, usage) -> immutableToolPerformance.put(name, usage.freeze()));
        return new McpUsageSummary(records.size(), successful, askCalls, delegationCalls, delegations, input, output, localCost,
                frontierEquivalentCost, Math.max(0, frontierEquivalentCost - localCost),
                delegations == 0 ? 0 : duration / delegations, totalPromptChars, totalResultChars, Map.copyOf(tools),
                Map.copyOf(immutableToolPerformance), Map.copyOf(immutableProviders), List.copyOf(callerModelsSeen));
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

    private static final class MutableToolPerformance {
        int calls;
        int successes;
        int timeouts;
        int contextRejections;
        int fallbacks;
        long totalResultChars;
        final java.util.ArrayList<Long> durations = new java.util.ArrayList<>();

        void add(McpUsageRecord record) {
            calls++;
            totalResultChars += record.resultChars();
            if (record.success()) successes++;
            if ("timeout".equals(record.errorType())) timeouts++;
            if ("context_rejected".equals(record.errorType())) contextRejections++;
            if (record.delegationAttempted() && !record.success()) fallbacks++;
            durations.add(record.durationMs());
        }

        ToolPerformance freeze() {
            durations.sort(Long::compareTo);
            long avg = calls == 0 ? 0 : totalResultChars / calls;
            return new ToolPerformance(calls, successes, timeouts, contextRejections, fallbacks,
                    percentile(0.50), percentile(0.95), avg);
        }

        private long percentile(double percentile) {
            if (durations.isEmpty()) return 0;
            int index = Math.max(0, (int) Math.ceil(percentile * durations.size()) - 1);
            return durations.get(index);
        }
    }
}
