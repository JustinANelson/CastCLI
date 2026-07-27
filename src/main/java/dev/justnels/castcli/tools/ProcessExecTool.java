package dev.justnels.castcli.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.execAllowed = execAllowed;
        this.approvalGate = approvalGate == null ? AutoApprovalGate.INSTANCE : approvalGate;
        this.sandboxGuard = new ProcessSandboxGuard(this.workingDirectory, TIMEOUT_SECONDS, MAX_OUTPUT_CHARS);
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
        dev.justnels.castcli.audit.AuditLogger.getInstance().log("PROCESS_EXEC", "harness", "runCommand", commandKey, "SUCCESS", java.util.Map.of("command", cmdStr));
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
        sandboxGuard.sanitizeEnvironment(builder.environment());
        Process process = builder.start();
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean finished;
        try {
            finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return "Command interrupted before completion.";
        }
        if (!finished) {
            process.destroyForcibly();
            return "Command timed out after " + TIMEOUT_SECONDS + "s and was terminated.\n" + truncate(output);
        }
        String truncated = truncate(output);
        return "Exit code: " + process.exitValue() + "\n" + truncated;
    }

    private static String truncate(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_OUTPUT_CHARS) + "\n...[truncated " + (output.length() - MAX_OUTPUT_CHARS) + " chars]";
    }
}

