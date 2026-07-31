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
 *
 * <p>When a {@code callerModel} string is available (supplied via the MCP {@code _meta.callerModel}
 * argument), the estimator first searches enabled providers for a case-insensitive {@code modelName} match
 * and uses that provider's rates as the reference, falling back to the default FRONTIER_CLOUD provider.
 */
public final class CostSavingsEstimator {
    private final HarnessConfig config;
    private final ProviderConfig referenceFrontierProvider;

    public CostSavingsEstimator(HarnessConfig config) {
        this.config = config;
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

    /**
     * Resolves the best-match provider for a caller model name string.
     * Searches all providers by case-insensitive {@code modelName} equality, then falls back
     * to the default FRONTIER_CLOUD provider (enabled or disabled reference spec).
     */
    public Optional<ProviderConfig> resolveCallerProvider(String callerModel) {
        if (callerModel != null && !callerModel.isBlank()) {
            Optional<ProviderConfig> byName = config.providers().stream()
                    .filter(p -> callerModel.equalsIgnoreCase(p.modelName()))
                    .findFirst();
            if (byName.isPresent()) return byName;
        }
        if (referenceFrontierProvider != null) {
            return Optional.of(referenceFrontierProvider);
        }
        return config.providers().stream()
                .filter(p -> p.tier() == ModelTier.FRONTIER_CLOUD)
                .findFirst();
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

    /**
     * Variant that also accepts a {@code callerModel} hint (from {@code _meta.callerModel}).
     * When non-null, uses the best-matched provider's rates as the frontier reference instead of the
     * default FRONTIER_CLOUD provider. Falls back identically to
     * {@link #estimateAvoidedCostUsd(ProviderConfig, long, long)} when no match is found.
     */
    public double estimateAvoidedCostUsd(ProviderConfig actualProvider, long inputTokens, long outputTokens,
                                         String callerModel) {
        if (!isOffloaded(actualProvider)) return 0.0;
        ProviderConfig reference = resolveCallerProvider(callerModel).orElse(null);
        if (reference == null) return 0.0;
        return reference.estimatedCostUsd(inputTokens, outputTokens);
    }
}

