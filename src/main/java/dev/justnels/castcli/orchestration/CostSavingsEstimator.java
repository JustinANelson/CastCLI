package dev.justnels.castcli.orchestration;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;

import java.util.Optional;

/**
 * Estimates the FRONTIER_CLOUD tokens and cost avoided by routing a call to a cheaper SMALL_LOCAL or
 * LARGE_LOCAL tier instead. Local/small tiers typically bill nothing per token, so "savings" here means:
 * had the same input/output token counts been processed by a configured frontier model, what would that
 * have cost at its per-million-token rate? The first enabled FRONTIER_CLOUD provider in the config is used
 * as that reference point; if none is configured, every estimate is zero.
 */
public final class CostSavingsEstimator {
    private final ProviderConfig referenceFrontierProvider;

    public CostSavingsEstimator(HarnessConfig config) {
        this.referenceFrontierProvider = config.providers().stream()
                .filter(p -> p.tier() == ModelTier.FRONTIER_CLOUD)
                .filter(ProviderConfig::enabled)
                .findFirst()
                .orElse(null);
    }

    public boolean hasReference() {
        return referenceFrontierProvider != null;
    }

    public Optional<ProviderConfig> referenceProvider() {
        return Optional.ofNullable(referenceFrontierProvider);
    }

    /** True when {@code actualProvider} is a tier whose tokens would otherwise count as "offloaded" from
     * a frontier model (i.e. it isn't FRONTIER_CLOUD itself). */
    public boolean isOffloaded(ProviderConfig actualProvider) {
        return actualProvider.tier() != ModelTier.FRONTIER_CLOUD;
    }

    /** Estimated USD cost had {@code inputTokens}/{@code outputTokens} instead been processed by the
     * reference frontier provider. Returns 0 when {@code actualProvider} is already FRONTIER_CLOUD (there's
     * nothing avoided -- those tokens were actually spent) or no frontier provider is configured. */
    public double estimateAvoidedCostUsd(ProviderConfig actualProvider, long inputTokens, long outputTokens) {
        if (referenceFrontierProvider == null || !isOffloaded(actualProvider)) {
            return 0.0;
        }
        return referenceFrontierProvider.estimatedCostUsd(inputTokens, outputTokens);
    }
}

