package dev.justnels.castcli.reliability;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BudgetExceededExceptionTest {

    @Test
    void verifiesExceptionGetters() {
        BudgetExceededException exception = new BudgetExceededException("Budget limit exceeded", 10.0, 15.5);
        assertThat(exception.getMessage()).isEqualTo("Budget limit exceeded");
        assertThat(exception.getLimitUsd()).isEqualTo(10.0);
        assertThat(exception.getActualUsd()).isEqualTo(15.5);
    }
}
