package dev.justnels.castcli.tools;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessExecToolTrimmingTest {

    @Test
    void truncatesOutputExceedingMaxChars() throws IOException {
        String longText = "A".repeat(5_000);
        ByteArrayInputStream stream = new ByteArrayInputStream(longText.getBytes(StandardCharsets.UTF_8));

        String captured = ProcessExecTool.captureOutput(stream, 1_000);

        assertThat(captured).startsWith("A".repeat(1_000));
        assertThat(captured).contains("...[truncated 4000 chars]");
    }
}
