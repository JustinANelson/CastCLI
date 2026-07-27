package dev.justnels.castcli.reliability;

import dev.justnels.castcli.config.ReliabilityConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetCircuitBreakerTest {

    @Test
    void allowsExecutionWithinBudgetLimits() {
        ReliabilityConfig config = new ReliabilityConfig(
                2, 100, 2000, 3, 30, 300, 16, Map.of(), 5.0, 10.0
        );
        ReliabilityExecutor executor = new ReliabilityExecutor(config, new ProviderHealthRegistry(config));

        executor.recordCost(2.50);
        assertThat(executor.getCumulativeCostUsd()).isEqualTo(2.50);

        executor.checkBudgetLimits(1.0);
    }

    @Test
    void throwsBudgetExceededWhenPerTaskLimitBreached() {
        ReliabilityConfig config = new ReliabilityConfig(
                2, 100, 2000, 3, 30, 300, 16, Map.of(), 1.0, 10.0
        );
        ReliabilityExecutor executor = new ReliabilityExecutor(config, new ProviderHealthRegistry(config));

        assertThatThrownBy(() -> executor.checkBudgetLimits(2.50))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("Single request estimated cost");
    }

    @Test
    void throwsBudgetExceededWhenCumulativeLimitBreached() {
        ReliabilityConfig config = new ReliabilityConfig(
                2, 100, 2000, 3, 30, 300, 16, Map.of(), 5.0, 3.00
        );
        ReliabilityExecutor executor = new ReliabilityExecutor(config, new ProviderHealthRegistry(config));

        executor.recordCost(2.00);
        assertThatThrownBy(() -> executor.recordCost(2.00))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("Cumulative execution cost");
    }
}
