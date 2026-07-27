package dev.justnels.castcli.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessExecToolTest {
    @TempDir
    Path workDir;

    @Test
    void deniedWhenExecNotAllowed() throws Exception {
        ProcessExecTool tool = new ProcessExecTool(workDir, false, AutoApprovalGate.INSTANCE);
        assertThat(tool.runCommand("git-status")).contains("Execution denied");
    }

    @Test
    void deniedByApprovalGate() throws Exception {
        ProcessExecTool tool = new ProcessExecTool(workDir, true, (action, detail) -> false);
        assertThat(tool.runCommand("git-status")).contains("denied by approval gate");
    }

    @Test
    void rejectsUnknownCommandKey() throws Exception {
        ProcessExecTool tool = new ProcessExecTool(workDir, true, AutoApprovalGate.INSTANCE);
        assertThat(tool.runCommand("rm -rf /")).contains("Unknown commandKey");
    }

    @Test
    void runsAllowListedGitStatus() throws Exception {
        ProcessBuilder init = new ProcessBuilder("git", "init", "-q").directory(workDir.toFile());
        init.start().waitFor();

        ProcessExecTool tool = new ProcessExecTool(workDir, true, AutoApprovalGate.INSTANCE);
        String result = tool.runCommand("git-status");
        assertThat(result).contains("Exit code: 0");
    }

    @Test
    void boundsCapturedOutputWhileContinuingToDrainTheStream() throws Exception {
        String output = ProcessExecTool.captureOutput(new ByteArrayInputStream(
                "x".repeat(20_000).getBytes(StandardCharsets.UTF_8)), 1_000);

        assertThat(output).startsWith("x".repeat(1_000));
        assertThat(output).contains("truncated 19000 chars");
        assertThat(output.length()).isLessThan(1_100);
    }

    @Test
    void timesOutAProcessThatKeepsOutputOpen() throws Exception {
        installSlowGradleWrapper();
        ProcessExecTool tool = new ProcessExecTool(workDir, true, AutoApprovalGate.INSTANCE, 1, 1_000);

        long started = System.nanoTime();
        String result = tool.runCommand("gradle-test");
        long elapsedSeconds = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);

        assertThat(result).contains("timed out after 1s");
        assertThat(elapsedSeconds).isLessThan(7);
    }

    private void installSlowGradleWrapper() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            Files.writeString(workDir.resolve("gradlew.bat"), """
                    @echo off
                    echo started
                    powershell.exe -NoProfile -Command "Start-Sleep -Seconds 10"
                    """);
            return;
        }
        Path wrapper = Files.writeString(workDir.resolve("gradlew"), """
                #!/bin/sh
                echo started
                sleep 10
                """);
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}

