package dev.justnels.castcli.reliability;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void permitsAcquisitionWithinCapacity() {
        RateLimiter limiter = new RateLimiter(3, Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse(); // exceeded
        assertThat(limiter.availablePermits()).isZero();
    }

    @Test
    void permitsReplenishAfterWindowDuration() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(2, Duration.ofMillis(100));
        assertThat(limiter.tryAcquire(2)).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        Thread.sleep(150);
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    void acquireBlocksUntilPermitAvailable() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(1, Duration.ofMillis(100));
        assertThat(limiter.tryAcquire()).isTrue();

        long start = System.currentTimeMillis();
        boolean acquired = limiter.acquire(1, 500);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(acquired).isTrue();
        assertThat(elapsed).isGreaterThanOrEqualTo(50);
    }
}
