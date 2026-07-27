package dev.justnels.castcli.orchestration;

import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Compiles actual token usage per provider across a sequence of {@link HarnessOrchestrator.Outcome}s (e.g.
 * every PM/worker call in one {@code AgentTeam.commission} run) and compares the totals that landed on
 * FRONTIER_CLOUD providers against the totals that landed on SMALL_LOCAL/LARGE_LOCAL ones. Unlike
 * {@link CostSavingsEstimator}, which estimates a hypothetical "what would this have cost on a frontier
 * model," this reports what actually happened, broken out per provider.
 *
 * <p>{@link #record} is safe to call concurrently (e.g. from the parallel worker waves in {@code AgentTeam}).
 */
public final class TokenUsageReport {
    private final Map<String, Accumulator> byProvider = new ConcurrentHashMap<>();

    private static final class Accumulator {
        final ModelTier tier;
        final AtomicLong calls = new AtomicLong();
        final AtomicLong inputTokens = new AtomicLong();
        final AtomicLong outputTokens = new AtomicLong();
        final DoubleAdder costUsd = new DoubleAdder();

        Accumulator(ModelTier tier) {
            this.tier = tier;
        }
    }

    public record ProviderUsage(
            String providerId, ModelTier tier, long calls, long inputTokens, long outputTokens, double estimatedCostUsd) {
        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    public record Summary(List<ProviderUsage> byProvider, long cloudTokens, long localTokens) {
        public long totalTokens() {
            return cloudTokens + localTokens;
        }

        /** Fraction of {@link #totalTokens()} that ran on a FRONTIER_CLOUD provider, from 0.0 to 1.0.
         * 0.0 when nothing has run yet, rather than dividing by zero. */
        public double cloudShare() {
            long total = totalTokens();
            return total == 0 ? 0.0 : (double) cloudTokens / total;
        }

        /** Positive when more tokens ran locally than in the cloud, negative when the reverse. */
        public long localMinusCloudTokens() {
            return localTokens - cloudTokens;
        }
    }

    /** Folds one outcome's tokens into the running per-provider tally. */
    public void record(HarnessOrchestrator.Outcome outcome) {
        ProviderConfig provider = outcome.provider();
        Accumulator accumulator = byProvider.computeIfAbsent(provider.id(), id -> new Accumulator(provider.tier()));
        accumulator.calls.incrementAndGet();
        accumulator.inputTokens.addAndGet(outcome.inputTokens());
        accumulator.outputTokens.addAndGet(outcome.outputTokens());
        accumulator.costUsd.add(outcome.estimatedCostUsd());
    }

    /** Compiles the current per-provider tallies into an immutable, cloud-vs-local comparison. */
    public Summary summarize() {
        List<ProviderUsage> providers = byProvider.entrySet().stream()
                .map(entry -> new ProviderUsage(
                        entry.getKey(), entry.getValue().tier, entry.getValue().calls.get(),
                        entry.getValue().inputTokens.get(), entry.getValue().outputTokens.get(),
                        entry.getValue().costUsd.sum()))
                .sorted(Comparator.comparing(ProviderUsage::providerId))
                .toList();

        long cloudTokens = providers.stream()
                .filter(p -> p.tier() == ModelTier.FRONTIER_CLOUD)
                .mapToLong(ProviderUsage::totalTokens)
                .sum();
        long localTokens = providers.stream()
                .filter(p -> p.tier() != ModelTier.FRONTIER_CLOUD)
                .mapToLong(ProviderUsage::totalTokens)
                .sum();

        return new Summary(providers, cloudTokens, localTokens);
    }
}

