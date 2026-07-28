package dev.justnels.castcli.mcp;

import java.util.Set;

/** One durable MCP tool invocation, including local-model usage for structured and generic delegations. */
public record McpUsageRecord(
        long timestampEpochMs,
        String invocationId,
        String traceId,
        String toolName,
        boolean success,
        long durationMs,
        String providerId,
        String providerTier,
        String modelName,
        long inputTokens,
        long outputTokens,
        double estimatedCostUsd,
        String promptSha256,
        int promptChars,
        int resultChars,
        String errorType,
        String callerModel) {
    private static final Set<String> DELEGATION_TOOLS = Set.of(
            "ask_local", "summarize_files", "analyze_failure", "draft_patch",
            "generate_tests", "review_diff", "map_change_impact");

    public McpUsageRecord(
            long timestampEpochMs, String invocationId, String traceId, String toolName, boolean success,
            long durationMs, String providerId, String providerTier, String modelName, long inputTokens,
            long outputTokens, double estimatedCostUsd, String promptSha256, int promptChars,
            int resultChars, String errorType) {
        this(timestampEpochMs, invocationId, traceId, toolName, success, durationMs, providerId, providerTier,
                modelName, inputTokens, outputTokens, estimatedCostUsd, promptSha256, promptChars, resultChars,
                errorType, null);
    }


    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    public boolean delegated() {
        return delegationAttempted() && success && providerId != null;
    }

    public boolean delegationAttempted() {
        return DELEGATION_TOOLS.contains(toolName);
    }
}
