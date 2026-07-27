package dev.justnels.castcli.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaShellToolTest {
    @Test
    void remainsDisabledByDefault() {
        assertThat(new JavaShellTool(false).evaluateJava("System.exit(0)"))
                .contains("disabled");
    }

    @Test
    void evaluatesAnExpressionWhenEnabled() {
        assertThat(new JavaShellTool(true).evaluateJava("6 * 7"))
                .contains("42");
    }
}

