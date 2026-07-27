package dev.justnels.castcli.reliability;

import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.ReliabilityConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReliabilityExecutorTest {
    private final ProviderConfig provider = new ProviderConfig("local", ModelTier.SMALL_LOCAL,
            "http://localhost/v1/", "model", null, 0, 30, false, true);

    @Test
    void retriesTransientFailuresAndRecordsRecovery() {
        ReliabilityConfig config = new ReliabilityConfig(3, 0, 0, 3, 30, 5, 2, Map.of());
        ProviderHealthRegistry health = new ProviderHealthRegistry(config);
        ReliabilityExecutor executor = new ReliabilityExecutor(config, health);
        AtomicInteger calls = new AtomicInteger();

        String result = executor.execute(provider, () -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("503 temporarily unavailable");
            return "ok";
        }, true, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        assertThat(health.consecutiveFailures(provider)).isZero();
    }

    @Test
    void doesNotRetryAuthenticationOrToolBearingOperations() {
        ReliabilityConfig config = new ReliabilityConfig(3, 0, 0, 3, 30, 5, 2, Map.of());
        ProviderHealthRegistry health = new ProviderHealthRegistry(config);
        ReliabilityExecutor executor = new ReliabilityExecutor(config, health);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(provider, () -> {
            calls.incrementAndGet(); throw new IllegalStateException("401 invalid api key");
        }, true, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)))
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(failure -> ((ProviderExecutionException) failure).kind()).isEqualTo(FailureKind.AUTHENTICATION);
        assertThat(calls).hasValue(1);
    }

    @Test
    void opensCircuitAfterThresholdAndCancelsAtDeadline() {
        ReliabilityConfig config = new ReliabilityConfig(1, 0, 0, 2, 30, 5, 2, Map.of());
        ProviderHealthRegistry health = new ProviderHealthRegistry(config);
        ReliabilityExecutor executor = new ReliabilityExecutor(config, health);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> executor.execute(provider,
                    () -> { throw new IllegalStateException("503 unavailable"); }, true,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1))).isInstanceOf(ProviderExecutionException.class);
        }
        assertThat(health.isAvailable(provider)).isFalse();

        ProviderConfig other = new ProviderConfig("other", ModelTier.SMALL_LOCAL, "http://localhost/v1/", "model", null, 0, 30, false, true);
        assertThatThrownBy(() -> executor.execute(other, () -> { Thread.sleep(500); return "late"; }, true,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(30)))
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(failure -> ((ProviderExecutionException) failure).kind()).isEqualTo(FailureKind.TIMEOUT);
    }
}
