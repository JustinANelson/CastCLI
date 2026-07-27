package dev.justnels.castcli.agent;

import dev.justnels.castcli.orchestration.TokenUsageReport;

import java.nio.file.Path;
import java.util.List;

public record CommissioningResult(
        ProjectPlan plan,
        List<SubTask> completedTasks,
        String commissioningSummary,
        long totalDurationMs,
        long totalInputTokens,
        long totalOutputTokens,
        double estimatedCostUsd,
        Path checkpointPath,
        long tokensOffloadedToLocal,
        double estimatedFrontierCostAvoidedUsd,
        String referenceFrontierModel,
        TokenUsageReport.Summary tokenUsageByProvider) {
}

