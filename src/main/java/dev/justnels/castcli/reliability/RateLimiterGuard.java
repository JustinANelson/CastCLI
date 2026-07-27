package dev.justnels.castcli.reliability;

import dev.justnels.castcli.config.ProviderConfig;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe sliding window rate limiter tracking requests per minute (RPM)
 * and tokens per minute (TPM) for each provider.
 */
public final class RateLimiterGuard {
    private final int maxRequestsPerMinute;
    private final long maxTokensPerMinute;
    private final Clock clock;
    private final Map<String, SlidingWindowTracker> trackers = new ConcurrentHashMap<>();

    public RateLimiterGuard(int maxRequestsPerMinute, long maxTokensPerMinute) {
        this(maxRequestsPerMinute, maxTokensPerMinute, Clock.systemUTC());
    }

    public RateLimiterGuard(int maxRequestsPerMinute, long maxTokensPerMinute, Clock clock) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.maxTokensPerMinute = maxTokensPerMinute;
        this.clock = clock;
    }

    public synchronized boolean tryAcquire(ProviderConfig provider) {
        if (maxRequestsPerMinute <= 0 && maxTokensPerMinute <= 0) return true;
        SlidingWindowTracker tracker = tracker(provider.id());
        long now = clock.millis();
        tracker.cleanOldEntries(now);
        if (maxRequestsPerMinute > 0 && tracker.requestCount() >= maxRequestsPerMinute) {
            return false;
        }
        tracker.recordRequest(now, 1);
        return true;
    }

    public synchronized void recordTokens(ProviderConfig provider, long tokens) {
        if (maxTokensPerMinute <= 0) return;
        SlidingWindowTracker tracker = tracker(provider.id());
        long now = clock.millis();
        tracker.recordTokens(now, tokens);
    }

    public synchronized boolean isTokenLimitExceeded(ProviderConfig provider) {
        if (maxTokensPerMinute <= 0) return false;
        SlidingWindowTracker tracker = tracker(provider.id());
        long now = clock.millis();
        tracker.cleanOldEntries(now);
        return tracker.tokenCount() >= maxTokensPerMinute;
    }

    private SlidingWindowTracker tracker(String providerId) {
        return trackers.computeIfAbsent(providerId, id -> new SlidingWindowTracker());
    }

    private static final class SlidingWindowTracker {
        private final java.util.Deque<TimestampedEvent> events = new java.util.ArrayDeque<>();

        void cleanOldEntries(long nowMs) {
            long threshold = nowMs - 60_000L;
            while (!events.isEmpty() && events.peekFirst().timestampMs < threshold) {
                events.removeFirst();
            }
        }

        int requestCount() {
            int count = 0;
            for (TimestampedEvent event : events) {
                if (event.isRequest) count++;
            }
            return count;
        }

        long tokenCount() {
            long total = 0;
            for (TimestampedEvent event : events) {
                total += event.tokens;
            }
            return total;
        }

        void recordRequest(long nowMs, long tokens) {
            events.addLast(new TimestampedEvent(nowMs, true, tokens));
        }

        void recordTokens(long nowMs, long tokens) {
            events.addLast(new TimestampedEvent(nowMs, false, tokens));
        }
    }

    private static final class TimestampedEvent {
        final long timestampMs;
        final boolean isRequest;
        final long tokens;

        TimestampedEvent(long timestampMs, boolean isRequest, long tokens) {
            this.timestampMs = timestampMs;
            this.isRequest = isRequest;
            this.tokens = tokens;
        }
    }
}
