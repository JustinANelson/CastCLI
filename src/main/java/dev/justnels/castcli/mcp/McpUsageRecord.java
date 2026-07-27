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
        String errorType) {
    private static final Set<String> DELEGATION_TOOLS = Set.of(
            "ask_local", "summarize_files", "analyze_failure", "draft_patch",
            "generate_tests", "review_diff", "map_change_impact");


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
