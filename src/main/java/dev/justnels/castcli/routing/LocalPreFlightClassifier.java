package dev.justnels.castcli.routing;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Pre-flight classifier that analyzes task complexity and privacy constraints
 * to maximize safe offloading of tasks to local model tiers (SMALL_LOCAL / LARGE_LOCAL).
 */
public final class LocalPreFlightClassifier {

    private static final Set<String> LOCAL_ELIGIBLE_KEYWORDS = Set.of(
            "format", "summarize", "search", "list", "check", "explain", "test",
            "log", "clean", "convert", "json", "diff", "review", "parse"
    );

    private static final Set<String> FRONTIER_REASONING_KEYWORDS = Set.of(
            "architectural design", "security audit", "cryptographic key", "production deployment",
            "auth policy", "threat model", "frontier reasoning"
    );

    private final HarnessConfig config;

    public LocalPreFlightClassifier(HarnessConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /** Classifies recommended tier for offloading task to local models whenever safe. */
    public ModelTier classifyTier(TaskRequest task) {
        Objects.requireNonNull(task, "task must not be null");

        if (task.requestedTier() != null) {
            return task.requestedTier();
        }

        String promptLower = task.prompt() == null ? "" : task.prompt().toLowerCase(Locale.ROOT);

        for (String keyword : FRONTIER_REASONING_KEYWORDS) {
            if (promptLower.contains(keyword)) {
                return ModelTier.FRONTIER_CLOUD;
            }
        }

        if (task.workload() == Workload.QUICK) {
            return ModelTier.SMALL_LOCAL;
        }

        if (task.workload() == Workload.CODE) {
            return hasEnabledTier(ModelTier.LARGE_LOCAL) ? ModelTier.LARGE_LOCAL : ModelTier.SMALL_LOCAL;
        }

        for (String keyword : LOCAL_ELIGIBLE_KEYWORDS) {
            if (promptLower.contains(keyword)) {
                return hasEnabledTier(ModelTier.SMALL_LOCAL) ? ModelTier.SMALL_LOCAL : ModelTier.LARGE_LOCAL;
            }
        }

        return hasEnabledTier(ModelTier.LARGE_LOCAL) ? ModelTier.LARGE_LOCAL : ModelTier.SMALL_LOCAL;
    }

    private boolean hasEnabledTier(ModelTier tier) {
        return config.providers().stream()
                .anyMatch(p -> p.enabled() && p.tier() == tier);
    }
}
