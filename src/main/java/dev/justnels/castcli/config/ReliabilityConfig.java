package dev.justnels.castcli.config;

import java.util.List;
import java.util.Map;

/** Retry, fallback, concurrency, budget guardrails, and circuit-breaker policy. */
public record ReliabilityConfig(
        int maxAttemptsPerProvider,
        long initialBackoffMillis,
        long maxBackoffMillis,
        int failureThreshold,
        int cooldownSeconds,
        int requestDeadlineSeconds,
        int maxConcurrentRequests,
        Map<String, List<String>> fallbackOrder,
        double maxCostUsdPerTask,
        double maxCumulativeCostUsd,
        int maxRequestsPerMinute,
        int mcpDelegationDeadlineSeconds) {

    public ReliabilityConfig {
        if (maxAttemptsPerProvider < 1) maxAttemptsPerProvider = 2;
        if (initialBackoffMillis < 0) initialBackoffMillis = 100;
        if (maxBackoffMillis < initialBackoffMillis) maxBackoffMillis = Math.max(initialBackoffMillis, 2_000);
        if (failureThreshold < 1) failureThreshold = 3;
        if (cooldownSeconds < 1) cooldownSeconds = 30;
        if (requestDeadlineSeconds < 1) requestDeadlineSeconds = 300;
        if (maxConcurrentRequests < 1) maxConcurrentRequests = 16;
        if (maxCostUsdPerTask < 0) maxCostUsdPerTask = 0.0;
        if (maxCumulativeCostUsd < 0) maxCumulativeCostUsd = 0.0;
        if (maxRequestsPerMinute < 0) maxRequestsPerMinute = 0;
        if (mcpDelegationDeadlineSeconds < 1) mcpDelegationDeadlineSeconds = 60;
        fallbackOrder = fallbackOrder == null ? Map.of() : fallbackOrder.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    public ReliabilityConfig(int maxAttemptsPerProvider, long initialBackoffMillis, long maxBackoffMillis,
                             int failureThreshold, int cooldownSeconds, int requestDeadlineSeconds,
                             int maxConcurrentRequests, Map<String, List<String>> fallbackOrder,
                             double maxCostUsdPerTask, double maxCumulativeCostUsd) {
        this(maxAttemptsPerProvider, initialBackoffMillis, maxBackoffMillis, failureThreshold,
                cooldownSeconds, requestDeadlineSeconds, maxConcurrentRequests, fallbackOrder,
                maxCostUsdPerTask, maxCumulativeCostUsd, 0, 60);
    }

    public ReliabilityConfig(int maxAttemptsPerProvider, long initialBackoffMillis, long maxBackoffMillis,
                             int failureThreshold, int cooldownSeconds, int requestDeadlineSeconds,
                             int maxConcurrentRequests, Map<String, List<String>> fallbackOrder) {
        this(maxAttemptsPerProvider, initialBackoffMillis, maxBackoffMillis, failureThreshold,
                cooldownSeconds, requestDeadlineSeconds, maxConcurrentRequests, fallbackOrder, 0.0, 0.0, 0, 60);
    }

    public static ReliabilityConfig defaults() {
        return new ReliabilityConfig(2, 100, 2_000, 3, 30, 300, 16, Map.of(), 0.0, 0.0, 0, 60);
    }
}
