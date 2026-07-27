package dev.justnels.castcli.orchestration;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.reliability.ProviderHealthRegistry;

import java.util.List;

/** Capability filter plus pluggable ranked routing policy. */
public final class ModelRouter {
    private final HarnessConfig config;
    private final RoutingStrategy strategy;
    private final ProviderHealthRegistry health;

    public ModelRouter(HarnessConfig config) {
        this(config, new PolicyRoutingStrategy(), new ProviderHealthRegistry(config.reliability()));
    }

    public ModelRouter(HarnessConfig config, RoutingStrategy strategy, ProviderHealthRegistry health) {
        this.config = config;
        this.strategy = strategy;
        this.health = health;
    }

    public ProviderConfig route(TaskRequest task) { return route(task, List.of()); }

    public ProviderConfig route(TaskRequest task, List<Object> selectedTools) {
        List<RoutingCandidate> ranked = rank(task, selectedTools);
        if (ranked.isEmpty()) {
            ModelTier target = task.requestedTier() == null ? classify(task, selectedTools) : task.requestedTier();
            String prefix = task.strict() ? "Strict routing requested tier " + target + " but " : "";
            throw new IllegalStateException(prefix + "no healthy, enabled, credentialed provider meets capability and privacy requirements");
        }
        return ranked.getFirst().provider();
    }

    public List<RoutingCandidate> rank(TaskRequest task, List<Object> selectedTools) {
        List<Object> tools = selectedTools == null ? List.of() : selectedTools;
        List<ProviderConfig> eligible = config.providers().stream()
                .filter(ProviderConfig::enabled)
                .filter(ProviderConfig::credentialsAvailable)
                .filter(provider -> tools.isEmpty() || (provider.toolsEnabled() && provider.effectiveMaxToolsSupported() >= tools.size()))
                .toList();
        return strategy.rank(task, tools, config, eligible, health);
    }

    ModelTier classify(TaskRequest task) { return classify(task, List.of()); }
    ModelTier classify(TaskRequest task, List<Object> tools) {
        if (strategy instanceof PolicyRoutingStrategy policy) return policy.classify(task, tools == null ? List.of() : tools, config);
        return task.requestedTier() == null ? ModelTier.LARGE_LOCAL : task.requestedTier();
    }
}
