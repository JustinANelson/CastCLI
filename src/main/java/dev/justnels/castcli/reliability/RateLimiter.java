package dev.justnels.castcli.reliability;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Thread-safe sliding-window rate limiter for protecting local Ollama nodes and
 * external model APIs against request rate limits and concurrency bursts.
 */
public final class RateLimiter {

    private final int maxPermitsPerWindow;
    private final long windowMillis;
    private final Deque<Long> timestamps;

    public RateLimiter(int maxPermitsPerWindow, Duration windowDuration) {
        if (maxPermitsPerWindow <= 0) {
            throw new IllegalArgumentException("maxPermitsPerWindow must be greater than zero");
        }
        Objects.requireNonNull(windowDuration, "windowDuration must not be null");
        if (windowDuration.isNegative() || windowDuration.isZero()) {
            throw new IllegalArgumentException("windowDuration must be positive");
        }

        this.maxPermitsPerWindow = maxPermitsPerWindow;
        this.windowMillis = windowDuration.toMillis();
        this.timestamps = new ArrayDeque<>(maxPermitsPerWindow);
    }

    /**
     * Attempts to acquire 1 permit without blocking.
     *
     * @return true if acquired; false if rate limit is exceeded
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Attempts to acquire the specified number of permits without blocking.
     *
     * @param permits number of permits requested
     * @return true if acquired; false if rate limit is exceeded
     */
    public synchronized boolean tryAcquire(int permits) {
        if (permits <= 0 || permits > maxPermitsPerWindow) {
            return false;
        }

        long now = System.currentTimeMillis();
        pruneExpired(now);

        if (timestamps.size() + permits <= maxPermitsPerWindow) {
            for (int i = 0; i < permits; i++) {
                timestamps.addLast(now);
            }
            return true;
        }
        return false;
    }

    /**
     * Acquires 1 permit, blocking up to {@code timeoutMillis} if necessary.
     *
     * @param timeoutMillis max time to wait in milliseconds
     * @return true if acquired before timeout expired; false otherwise
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean acquire(long timeoutMillis) throws InterruptedException {
        return acquire(1, timeoutMillis);
    }

    /**
     * Acquires the specified number of permits, blocking up to {@code timeoutMillis} if necessary.
     *
     * @param permits number of permits requested
     * @param timeoutMillis max time to wait in milliseconds
     * @return true if acquired before timeout expired; false otherwise
     * @throws InterruptedException if interrupted while waiting
     */
    public synchronized boolean acquire(int permits, long timeoutMillis) throws InterruptedException {
        if (permits <= 0 || permits > maxPermitsPerWindow) {
            return false;
        }

        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            long now = System.currentTimeMillis();
            pruneExpired(now);

            if (timestamps.size() + permits <= maxPermitsPerWindow) {
                for (int i = 0; i < permits; i++) {
                    timestamps.addLast(now);
                }
                return true;
            }

            long waitTime = deadline - now;
            if (waitTime <= 0) {
                return false;
            }

            Long oldest = timestamps.peekFirst();
            if (oldest != null) {
                long sleepTime = Math.min(waitTime, (oldest + windowMillis) - now + 1);
                if (sleepTime > 0) {
                    wait(sleepTime);
                }
            } else {
                wait(waitTime);
            }
        }
    }

    private void pruneExpired(long now) {
        long cutoff = now - windowMillis;
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
            timestamps.pollFirst();
        }
        notifyAll();
    }

    public synchronized int availablePermits() {
        pruneExpired(System.currentTimeMillis());
        return maxPermitsPerWindow - timestamps.size();
    }

    public int maxPermitsPerWindow() {
        return maxPermitsPerWindow;
    }
}
