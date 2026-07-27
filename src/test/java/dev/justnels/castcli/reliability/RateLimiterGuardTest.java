package dev.justnels.castcli.reliability;

import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterGuardTest {

    @Test
    void testRequestRateLimiting() {
        RateLimiterGuard guard = new RateLimiterGuard(2, 1000);
        ProviderConfig provider = new ProviderConfig("test-p", ModelTier.SMALL_LOCAL, "http://localhost:11434/v1/", "model", null, 0.1, 60, true, true, 4, 0.0, 0.0);

        assertTrue(guard.tryAcquire(provider), "1st request should be permitted");
        assertTrue(guard.tryAcquire(provider), "2nd request should be permitted");
        assertFalse(guard.tryAcquire(provider), "3rd request should exceed RPM limit");
    }

    @Test
    void testTokenRateLimiting() {
        RateLimiterGuard guard = new RateLimiterGuard(10, 500);
        ProviderConfig provider = new ProviderConfig("test-p", ModelTier.SMALL_LOCAL, "http://localhost:11434/v1/", "model", null, 0.1, 60, true, true, 4, 0.0, 0.0);

        guard.recordTokens(provider, 300);
        assertFalse(guard.isTokenLimitExceeded(provider), "300 tokens should be under 500 limit");

        guard.recordTokens(provider, 250);
        assertTrue(guard.isTokenLimitExceeded(provider), "550 tokens should exceed 500 limit");
    }
}
