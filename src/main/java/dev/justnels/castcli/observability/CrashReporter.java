package dev.justnels.castcli.observability;

import dev.justnels.castcli.doctor.BuildInfo;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

/**
 * Writes a redacted crash report to a local file when the CLI exits on an uncaught exception.
 * Local-only: this never makes a network call. It exists so a solo dev hitting a crash has a
 * file to paste into a bug report, instead of only a one-line stderr message.
 */
public final class CrashReporter {
    private static final Path DEFAULT_CRASH_DIR = Path.of(".cast", "crashes");

    private CrashReporter() {
    }

    /** Writes a crash report and returns its path, or {@code null} if writing failed. */
    public static Path write(Throwable error, String[] commandArgs) {
        return write(error, commandArgs, DEFAULT_CRASH_DIR);
    }

    static Path write(Throwable error, String[] commandArgs, Path crashDir) {
        try {
            Files.createDirectories(crashDir);
            Path file = crashDir.resolve("crash-" + Instant.now().toString().replace(":", "-") + ".log");

            StringWriter traceWriter = new StringWriter();
            error.printStackTrace(new PrintWriter(traceWriter));

            String report = "CastCLI crash report (local only -- never transmitted)\n"
                    + "timestamp: " + Instant.now() + "\n"
                    + "version:   " + BuildInfo.version() + "\n"
                    + "os:        " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n"
                    + "java:      " + System.getProperty("java.version") + "\n"
                    + "args:      " + SecretRedactor.redact(Arrays.toString(commandArgs)) + "\n"
                    + "\n"
                    + SecretRedactor.redact(traceWriter.toString());

            Files.writeString(file, report, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            return null;
        }
    }
}
