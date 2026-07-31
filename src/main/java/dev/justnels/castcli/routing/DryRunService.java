package dev.justnels.castcli.routing;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.orchestration.ModelRouter;
import dev.justnels.castcli.orchestration.RoutingCandidate;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import dev.justnels.castcli.tools.AutoApprovalGate;
import dev.justnels.castcli.tools.DefaultToolSelector;
import dev.justnels.castcli.tools.FastPathExecutor;

import java.util.ArrayList;
import java.util.List;

/**
 * Service performing zero-cost, inspectable dry-run policy routing evaluation.
 */
public final class DryRunService {

    public record CandidateSummary(
            String id,
            ModelTier tier,
            String modelName,
            double score,
            boolean eligible,
            List<String> reasons) {}

    public record DryRunReport(
            String promptPreview,
            Workload workload,
            ModelTier requestedTier,
            ModelTier classifiedTier,
            ProviderConfig selectedProvider,
            List<String> selectedTools,
            List<CandidateSummary> candidateRankings,
            List<CandidateSummary> excludedProviders,
            String privacyClassification,
            boolean cloudEgressExpected,
            String egressDestination,
            int estimatedInputTokens,
            double estimatedCostUsd,
            boolean fastPathEligible) {

        public String toHumanReadableString() {
            StringBuilder sb = new StringBuilder();
            sb.append("CastCLI Dry-Run Routing Report\n");
            sb.append("========================================\n");
            sb.append("Prompt: \"").append(promptPreview).append("\"\n");
            sb.append("Workload: ").append(workload).append("\n");
            sb.append("Requested Tier: ").append(requestedTier != null ? requestedTier : "(auto)").append("\n");
            sb.append("Classified Tier: ").append(classifiedTier).append("\n");
            sb.append("Selected Provider: ")
                    .append(selectedProvider != null ? selectedProvider.id() + " (" + selectedProvider.modelName() + ")" : "NONE")
                    .append("\n");
            sb.append("Fast-Path Eligible: ").append(fastPathEligible).append("\n");
            sb.append("Privacy Classification: ").append(privacyClassification).append("\n");
            sb.append("Cloud Egress Expected: ").append(cloudEgressExpected)
                    .append(cloudEgressExpected ? " -> " + egressDestination : " (local execution)").append("\n");
            sb.append("Estimated Input Tokens: ~").append(estimatedInputTokens).append("\n");
            sb.append("Estimated USD Cost: $").append(String.format("%.5f", estimatedCostUsd)).append("\n\n");

            sb.append("Selected Tools (").append(selectedTools.size()).append("):\n");
            if (selectedTools.isEmpty()) {
                sb.append("  (none)\n");
            } else {
                for (String t : selectedTools) {
                    sb.append("  - ").append(t).append("\n");
                }
            }
            sb.append("\nCandidate Rankings:\n");
            if (candidateRankings.isEmpty()) {
                sb.append("  (no eligible candidate found)\n");
            } else {
                for (int i = 0; i < candidateRankings.size(); i++) {
                    CandidateSummary c = candidateRankings.get(i);
                    sb.append(String.format("  %d. %-18s [%-14s] score=%-6.2f %s%n",
                            i + 1, c.id(), c.tier(), c.score(), String.join("; ", c.reasons())));
                }
            }

            if (!excludedProviders.isEmpty()) {
                sb.append("\nExcluded Providers:\n");
                for (CandidateSummary e : excludedProviders) {
                    sb.append(String.format("  - %-18s [%-14s] reason: %s%n",
                            e.id(), e.tier(), String.join("; ", e.reasons())));
                }
            }
            return sb.toString();
        }
    }

    public DryRunReport dryRun(HarnessConfig config, TaskRequest request) {
        DefaultToolSelector toolSelector = new DefaultToolSelector();
        List<Object> tools = toolSelector.selectTools(request, config.tools(), AutoApprovalGate.INSTANCE);
        List<String> toolNames = tools.stream().map(t -> t.getClass().getSimpleName()).toList();

        ModelRouter router = new ModelRouter(config);
        List<RoutingCandidate> ranked = router.rank(request, tools);

        ProviderConfig winner = ranked.isEmpty() ? null : ranked.getFirst().provider();
        ModelTier classified = winner != null ? winner.tier() : (request.requestedTier() != null ? request.requestedTier() : ModelTier.LARGE_LOCAL);

        List<CandidateSummary> rankings = new ArrayList<>();
        for (RoutingCandidate candidate : ranked) {
            rankings.add(new CandidateSummary(
                    candidate.provider().id(),
                    candidate.provider().tier(),
                    candidate.provider().modelName(),
                    candidate.score(),
                    true,
                    candidate.reasons()));
        }

        List<CandidateSummary> excluded = new ArrayList<>();
        for (ProviderConfig provider : config.providers()) {
            boolean isRanked = ranked.stream().anyMatch(r -> r.provider().id().equals(provider.id()));
            if (!isRanked) {
                List<String> reasons = new ArrayList<>();
                if (!provider.enabled()) {
                    reasons.add("disabled in config");
                }
                if (!provider.credentialsAvailable()) {
                    reasons.add("missing API key / credentials env (" + provider.apiKeyEnv() + ")");
                }
                if (!tools.isEmpty() && (!provider.toolsEnabled() || provider.effectiveMaxToolsSupported() < tools.size())) {
                    reasons.add("does not support " + tools.size() + " tools (supports " + provider.effectiveMaxToolsSupported() + ")");
                }
                if (request.strict() && request.requestedTier() != null && provider.tier() != request.requestedTier()) {
                    reasons.add("strict tier mismatch (requested " + request.requestedTier() + ", provider is " + provider.tier() + ")");
                }
                if (reasons.isEmpty()) {
                    reasons.add("ranked out by policy score");
                }
                excluded.add(new CandidateSummary(
                        provider.id(),
                        provider.tier(),
                        provider.modelName(),
                        0.0,
                        false,
                        reasons));
            }
        }

        String preview = request.prompt().length() > 60
                ? request.prompt().substring(0, 57) + "..."
                : request.prompt();

        boolean fastPath = new FastPathExecutor().executeIfPossible(request, config.tools()).isPresent();
        boolean cloudEgress = winner != null && winner.tier() == ModelTier.FRONTIER_CLOUD;
        String egressDest = cloudEgress ? winner.baseUrl() : "local";
        int estTokens = Math.max(1, request.prompt().length() / 4);
        double estCost = winner != null ? winner.estimatedCostUsd(estTokens, estTokens / 2) : 0.0;

        String privacyClass = request.prompt().contains("secret") || request.prompt().contains("password")
                ? "CONFIDENTIAL"
                : "PUBLIC";

        return new DryRunReport(
                preview,
                request.workload(),
                request.requestedTier(),
                classified,
                winner,
                toolNames,
                rankings,
                excluded,
                privacyClass,
                cloudEgress,
                egressDest,
                estTokens,
                estCost,
                fastPath);
    }
}
