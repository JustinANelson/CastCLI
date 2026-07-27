package dev.justnels.castcli.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs a small allow-list of build/VCS inspection commands so worker agents (e.g. TESTER)
 * can actually execute tests instead of only describing them. Arbitrary shell strings are
 * rejected: only a fixed set of subcommands may run, each as a direct process (no shell).
 */
public final class ProcessExecTool {
    private static final Map<String, List<String>> ALLOWED_COMMANDS = Map.ofEntries(
            Map.entry("gradle-test", List.of("gradlew", "test")),
            Map.entry("gradle-build", List.of("gradlew", "build")),
            Map.entry("gradle-check", List.of("gradlew", "check")),
            Map.entry("git-status", List.of("git", "status", "--short")),
            Map.entry("git-diff", List.of("git", "diff")),
            Map.entry("git-log", List.of("git", "log", "-n", "10", "--oneline")));

    private static final int MAX_OUTPUT_CHARS = 8_000;
    private static final long TIMEOUT_SECONDS = 120;

    private final Path workingDirectory;
    private final boolean execAllowed;
    private final ApprovalGate approvalGate;
    private final ProcessSandboxGuard sandboxGuard;

    public ProcessExecTool(Path workingDirectory, boolean execAllowed, ApprovalGate approvalGate) {
        this(workingDirectory, execAllowed, approvalGate, TIMEOUT_SECONDS, MAX_OUTPUT_CHARS);
    }

    ProcessExecTool(Path workingDirectory, boolean execAllowed, ApprovalGate approvalGate,
                    long timeoutSeconds, int maxOutputChars) {
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.execAllowed = execAllowed;
        this.approvalGate = approvalGate == null ? DenyApprovalGate.INSTANCE : approvalGate;
        this.sandboxGuard = new ProcessSandboxGuard(this.workingDirectory, timeoutSeconds, maxOutputChars);
    }

    @Tool("Runs a pre-approved build/test/VCS command. commandKey must be one of: "
            + "gradle-test, gradle-build, gradle-check, git-status, git-diff, git-log. "
            + "Requires tools.allowShellExec=true and, unless auto-approved, interactive confirmation.")
    public String runCommand(@P("one of the allow-listed command keys") String commandKey) throws IOException {
        if (!execAllowed) {
            return "Execution denied: tools.allowShellExec is false in the harness configuration.";
        }
        List<String> baseCommand = ALLOWED_COMMANDS.get(commandKey);
        if (baseCommand == null) {
            return "Unknown commandKey '" + commandKey + "'. Allowed: " + ALLOWED_COMMANDS.keySet();
        }
        List<String> command = resolveExecutable(baseCommand);
        sandboxGuard.validateWorkingDirectory(workingDirectory);
        sandboxGuard.validateCommandTokens(command);
        String cmdStr = String.join(" ", command);
        if (!approvalGate.approve("run command", cmdStr + " (cwd=" + workingDirectory + ")")) {
            dev.justnels.castcli.audit.AuditLogger.getInstance().log("PROCESS_EXEC", "harness", "runCommand", commandKey, "DENIED", java.util.Map.of("command", cmdStr));
            return "Execution denied by approval gate: " + commandKey;
        }
        String result = execute(command);
        String outcome = result.startsWith("Exit code: 0\n") ? "SUCCESS" : "FAILED";
        dev.justnels.castcli.audit.AuditLogger.getInstance().log(
                "PROCESS_EXEC", "harness", "runCommand", commandKey, outcome,
                java.util.Map.of("command", cmdStr));
        return result;
    }

    private List<String> resolveExecutable(List<String> baseCommand) {
        if (!baseCommand.get(0).equals("gradlew")) {
            return baseCommand;
        }
        String wrapper = System.getProperty("os.name", "").toLowerCase().contains("win") ? "gradlew.bat" : "./gradlew";
        return concat(wrapper, baseCommand.subList(1, baseCommand.size()));
    }

    private static List<String> concat(String head, List<String> tail) {
        java.util.ArrayList<String> merged = new java.util.ArrayList<>();
        merged.add(head);
        merged.addAll(tail);
        return merged;
    }

    private String execute(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(sandboxGuard.validateWorkingDirectory(workingDirectory).toFile())
                .redirectErrorStream(true);
        sandboxGuard.applySanitizedEnvironment(builder.environment());
        Process process = builder.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> output = executor.submit(() -> captureOutput(
                    process.getInputStream(), sandboxGuard.getMaxOutputChars()));
            boolean finished;
            try {
                finished = process.waitFor(sandboxGuard.getTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                terminateProcessTree(process);
                return "Command interrupted before completion.";
            }
            if (!finished) {
                terminateProcessTree(process);
                return "Command timed out after " + sandboxGuard.getTimeoutSeconds()
                        + "s and was terminated.\n" + completedOutput(output);
            }
            return "Exit code: " + process.exitValue() + "\n" + completedOutput(output);
        }
    }

    private static String completedOutput(Future<String> output) throws IOException {
        try {
            return output.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Output capture interrupted.";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Could not capture command output", cause);
        } catch (java.util.concurrent.TimeoutException e) {
            return "Output capture did not finish after process termination.";
        }
    }

    static String captureOutput(InputStream stream, int maxChars) throws IOException {
        StringBuilder captured = new StringBuilder(Math.min(maxChars, 8_000));
        long omitted = 0;
        char[] chunk = new char[2_048];
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            int read;
            while ((read = reader.read(chunk)) != -1) {
                int remaining = maxChars - captured.length();
                int accepted = Math.min(Math.max(remaining, 0), read);
                if (accepted > 0) {
                    captured.append(chunk, 0, accepted);
                }
                omitted += read - accepted;
            }
        }
        if (omitted > 0) {
            captured.append("\n...[truncated ").append(omitted).append(" chars]");
        }
        return captured.toString();
    }

    private static void terminateProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // The process may already have closed the stream.
        }
    }
}

