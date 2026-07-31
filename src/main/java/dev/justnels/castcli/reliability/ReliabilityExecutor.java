package dev.justnels.castcli.reliability;

import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.ReliabilityConfig;
import dev.justnels.castcli.observability.CastTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.DoubleAdder;

/** Enforces concurrency, tier throttling, bounded retry, exponential backoff, cost guardrails, cancellation, deadline, and health recording. */
public final class ReliabilityExecutor {
    private final ReliabilityConfig config;
    private final ProviderHealthRegistry health;
    private final RateLimiterGuard rateLimiter;
    private final FailureClassifier classifier = new FailureClassifier();
    private final Semaphore concurrency;
    private final ConcurrentMap<ModelTier, Semaphore> tierSemaphores = new ConcurrentHashMap<>();
    private final DoubleAdder cumulativeCostUsd = new DoubleAdder();

    public ReliabilityExecutor(ReliabilityConfig config, ProviderHealthRegistry health) {
        this(config, health, new RateLimiterGuard(config.maxRequestsPerMinute(), 100_000));
    }

    public ReliabilityExecutor(ReliabilityConfig config, ProviderHealthRegistry health, RateLimiterGuard rateLimiter) {
        this.config = config;
        this.health = health;
        this.rateLimiter = rateLimiter;
        this.concurrency = new Semaphore(config.maxConcurrentRequests(), true);
    }

    public double getCumulativeCostUsd() {
        return cumulativeCostUsd.sum();
    }

    public void recordCost(double costUsd) {
        if (costUsd > 0) {
            cumulativeCostUsd.add(costUsd);
            checkBudgetLimits(costUsd);
        }
    }

    public void checkBudgetLimits(double estimatedCallCost) {
        if (config.maxCostUsdPerTask() > 0 && estimatedCallCost > config.maxCostUsdPerTask()) {
            throw new BudgetExceededException("Single request estimated cost ($" + estimatedCallCost + ") exceeds limit ($" + config.maxCostUsdPerTask() + ")", config.maxCostUsdPerTask(), estimatedCallCost);
        }
        double currentTotal = cumulativeCostUsd.sum();
        if (config.maxCumulativeCostUsd() > 0 && currentTotal > config.maxCumulativeCostUsd()) {
            throw new BudgetExceededException("Cumulative execution cost ($" + currentTotal + ") exceeds limit ($" + config.maxCumulativeCostUsd() + ")", config.maxCumulativeCostUsd(), currentTotal);
        }
    }

    public <T> T execute(ProviderConfig provider, Callable<T> operation, boolean retrySafe, long deadlineNanos) {
        checkBudgetLimits(0.0);
        if (!health.isAvailable(provider)) throw new ProviderExecutionException(provider, FailureKind.TRANSIENT,
                new IllegalStateException("provider circuit is cooling down"));
        if (rateLimiter != null && !rateLimiter.tryAcquire(provider)) {
            throw new ProviderExecutionException(provider, FailureKind.TRANSIENT,
                    new IllegalStateException("rate limit (RPM) exceeded for provider " + provider.id()));
        }
        int attempts = retrySafe ? config.maxAttemptsPerProvider() : 1;
        long backoff = config.initialBackoffMillis();
        Throwable last = null;
        try (var providerSpan = CastTelemetry.current().span("castcli.provider.execute")
                .attribute("castcli.provider.id", provider.id())
                .attribute("gen_ai.request.model", provider.modelName())
                .attribute("castcli.retry.max_attempts", attempts)) {
          for (int attempt = 1; attempt <= attempts; attempt++) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) throw failure(provider, new TimeoutException("request deadline exceeded"));
            boolean acquiredGlobal = false;
            boolean acquiredTier = false;
            Semaphore tierSemaphore = (provider.tier() == ModelTier.SMALL_LOCAL || provider.tier() == ModelTier.LARGE_LOCAL)
                    ? tierSemaphores.computeIfAbsent(provider.tier(), t -> new Semaphore(Math.min(2, Math.max(1, config.maxConcurrentRequests() / 2)), true))
                    : null;
            var attemptSpan = CastTelemetry.current().span("castcli.provider.attempt")
                    .attribute("castcli.provider.id", provider.id())
                    .attribute("castcli.retry.attempt", attempt);
            try {
                acquiredGlobal = concurrency.tryAcquire(remaining, TimeUnit.NANOSECONDS);
                if (!acquiredGlobal) throw new TimeoutException("request concurrency queue deadline exceeded");

                if (tierSemaphore != null) {
                    long remainingAfterGlobal = deadlineNanos - System.nanoTime();
                    acquiredTier = tierSemaphore.tryAcquire(Math.max(1, remainingAfterGlobal), TimeUnit.NANOSECONDS);
                    if (!acquiredTier) throw new TimeoutException("tier concurrency queue deadline exceeded");
                }

                long start = System.nanoTime();
                T result = runWithDeadline(Context.current().wrap(operation), deadlineNanos);
                health.recordSuccess(provider, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure(provider, e);
            } catch (Throwable failure) {
                if (acquiredTier) {
                    tierSemaphore.release();
                    acquiredTier = false;
                }
                if (acquiredGlobal) {
                    concurrency.release();
                    acquiredGlobal = false;
                }
                last = unwrap(failure);
                FailureKind kind = classifier.classify(last);
                attemptSpan.attribute("castcli.failure.kind", kind.name()).error(last);
                health.recordFailure(provider, kind);
                if (!kind.retryable() || attempt == attempts) throw new ProviderExecutionException(provider, kind, last);
                CastTelemetry.current().retry(Attributes.builder()
                        .put("castcli.provider.id", provider.id())
                        .put("castcli.failure.kind", kind.name()).build());
                providerSpan.event("provider.retry", Attributes.builder()
                        .put("castcli.retry.attempt", attempt)
                        .put("castcli.failure.kind", kind.name()).build());
                long jittered = backoff <= 1 ? backoff : ThreadLocalRandom.current().nextLong(Math.max(1, backoff / 2), backoff + 1);
                sleep(Math.min(jittered, TimeUnit.NANOSECONDS.toMillis(Math.max(0, deadlineNanos - System.nanoTime()))));
                backoff = Math.min(config.maxBackoffMillis(), Math.max(1, backoff * 2));
            } finally {
                if (acquiredTier) tierSemaphore.release();
                if (acquiredGlobal) concurrency.release();
                attemptSpan.close();
            }
          }
        } catch (RuntimeException failure) {
            CastTelemetry.current().failure(Attributes.builder()
                    .put("castcli.operation", "provider.execute")
                    .put("castcli.provider.id", provider.id()).build());
            throw failure;
        }
        throw failure(provider, last == null ? new IllegalStateException("provider failed") : last);
    }

    private static <T> T runWithDeadline(Callable<T> operation, long deadlineNanos) throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<T> future = executor.submit(operation);
            try {
                return future.get(Math.max(1, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw e;
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception exception ? exception : new RuntimeException(e.getCause());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }

    private ProviderExecutionException failure(ProviderConfig provider, Throwable cause) {
        return new ProviderExecutionException(provider, classifier.classify(cause), cause);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof ProviderExecutionException providerFailure && providerFailure.getCause() != null
                ? providerFailure.getCause() : failure;
    }
}
