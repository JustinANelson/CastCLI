package dev.justnels.castcli.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CrashReporterTest {

    @Test
    void writesRedactedStackTraceToLocalFile(@TempDir Path tempDir) throws Exception {
        Path crashDir = tempDir.resolve(".cast/crashes");
        Exception error = new IllegalStateException("failed with api_key=sk-abcdefghijklmnopqrstuvwx");

        Path crashFile = CrashReporter.write(error, new String[] {"ask", "hello"}, crashDir);

        assertThat(crashFile).isNotNull();
        assertThat(Files.exists(crashFile)).isTrue();
        String content = Files.readString(crashFile);
        assertThat(content).contains("CastCLI crash report (local only")
                .contains("IllegalStateException")
                .contains("[REDACTED_KEY]")
                .doesNotContain("sk-abcdefghijklmnopqrstuvwx");
    }
}
