package dev.justnels.castcli.orchestration;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.reliability.ProviderHealthRegistry;
import dev.justnels.castcli.tools.JavaShellTool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Explainable multi-signal policy: capability, privacy, tier fit, locality, cost, latency, and health. */
public final class PolicyRoutingStrategy implements RoutingStrategy {
    private static final List<String> REASONING_MARKERS = List.of(
            "analyze", "architecture", "compare", "design", "prove", "reason", "research", "tradeoff");
    private static final List<String> CODE_MARKERS = List.of(
            "code", "compile", "debug", "gradle", "implement", "java", "refactor", "repository", "test");
    private static final List<String> PRIVATE_MARKERS = List.of(
            "confidential", "private", "secret", "credential", "customer data", "personal data", "pii", "do not upload");

    @Override
    public List<RoutingCandidate> rank(TaskRequest task, List<Object> selectedTools, HarnessConfig config,
                                       List<ProviderConfig> eligible, ProviderHealthRegistry health) {
        ModelTier target = task.requestedTier() == null ? classify(task, selectedTools, config) : task.requestedTier();
        boolean privacySensitive = containsAny(task.prompt().toLowerCase(Locale.ROOT), PRIVATE_MARKERS);
        List<RoutingCandidate> candidates = new ArrayList<>();
        for (ProviderConfig provider : eligible) {
            if (!health.isAvailable(provider)) continue;
            if (task.strict() && provider.tier() != target) continue;
            if (privacySensitive && task.requestedTier() == null && provider.tier() == ModelTier.FRONTIER_CLOUD) continue;
            List<String> reasons = new ArrayList<>();
            int distance = Math.abs(provider.tier().ordinal() - target.ordinal());
            double score = 100 - distance * 35;
            reasons.add("tier-distance=" + distance);
            if (provider.tier() != ModelTier.FRONTIER_CLOUD && config.routing().preferLocal()) {
                score += 12; reasons.add("local-preference");
            }
            if (privacySensitive && provider.tier() != ModelTier.FRONTIER_CLOUD) {
                score += 40; reasons.add("privacy-locality");
            }
            double tokenCost = provider.costPerMillionInputTokens() + provider.costPerMillionOutputTokens();
            double costScore = 18.0 / (1.0 + tokenCost);
            score += costScore; reasons.add(String.format(Locale.ROOT, "cost=%.2f", costScore));
            long latency = health.observedLatencyMs(provider);
            double latencyScore = 12.0 / (1.0 + latency / 1_000.0);
            score += latencyScore; reasons.add("latency-ms=" + latency);
            int failures = health.consecutiveFailures(provider);
            score -= failures * 8.0;
            if (failures > 0) reasons.add("recent-failures=" + failures);
            if (!selectedTools.isEmpty()) {
                score += Math.min(8, provider.effectiveMaxToolsSupported() - selectedTools.size());
                reasons.add("tool-capacity");
            }
            candidates.add(new RoutingCandidate(provider, score, reasons));
        }
        return candidates.stream().sorted(Comparator.comparingDouble(RoutingCandidate::score).reversed()
                .thenComparing(candidate -> candidate.provider().id())).toList();
    }

    ModelTier classify(TaskRequest task, List<Object> selectedTools, HarnessConfig config) {
        ModelTier tier = switch (task.workload()) {
            case QUICK -> ModelTier.SMALL_LOCAL;
            case CODE -> ModelTier.LARGE_LOCAL;
            case REASONING -> ModelTier.FRONTIER_CLOUD;
            case AUTO -> classifyPrompt(task.prompt(), config);
        };
        boolean complex = selectedTools.stream().anyMatch(JavaShellTool.class::isInstance) || selectedTools.size() > 1;
        return complex && tier == ModelTier.SMALL_LOCAL ? ModelTier.LARGE_LOCAL : tier;
    }

    private static ModelTier classifyPrompt(String prompt, HarnessConfig config) {
        String normalized = prompt.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, REASONING_MARKERS)) return config.routing().preferLocal() ? ModelTier.LARGE_LOCAL : ModelTier.FRONTIER_CLOUD;
        if (containsAny(normalized, CODE_MARKERS)) return ModelTier.LARGE_LOCAL;
        return prompt.length() <= config.routing().quickPromptMaxChars() ? ModelTier.SMALL_LOCAL : ModelTier.LARGE_LOCAL;
    }

    private static boolean containsAny(String input, List<String> markers) { return markers.stream().anyMatch(input::contains); }
}
