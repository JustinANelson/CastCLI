package dev.justnels.castcli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.justnels.castcli.observability.CastTelemetry;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import dev.justnels.castcli.tools.WorkspaceTools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Builds bounded, read-only task packets for local models. The model may propose code or a diff, but
 * these tools never mutate the workspace or execute a process.
 */
final class ReadOnlyDelegationTools {
    private static final int MAX_PATHS = 20;
    private static final int MAX_PARTITIONS = 4;
    private static final int MAX_SEARCH_RESULTS = 50;
    private static final long DEFAULT_DEADLINE_MILLIS = 60_000;

    private final HarnessOrchestrator orchestrator;
    private final WorkspaceTools workspaceTools;
    private final CastTelemetry telemetry;
    private final int maxContextChars;
    private final int promptBudgetChars;
    private final int resultBudgetChars;
    private final long deadlineMillis;
    private final ModelTier quickTier;
    private final ModelTier codeTier;

    ReadOnlyDelegationTools(HarnessOrchestrator orchestrator, WorkspaceTools workspaceTools,
                            CastTelemetry telemetry, int maxContextChars) {
        this(orchestrator, workspaceTools, telemetry, maxContextChars, DEFAULT_DEADLINE_MILLIS);
    }

    ReadOnlyDelegationTools(HarnessOrchestrator orchestrator, WorkspaceTools workspaceTools,
                            CastTelemetry telemetry, int maxContextChars, long deadlineMillis) {
        this(orchestrator, workspaceTools, telemetry, maxContextChars, deadlineMillis,
                ModelTier.SMALL_LOCAL, ModelTier.LARGE_LOCAL);
    }

    ReadOnlyDelegationTools(HarnessOrchestrator orchestrator, WorkspaceTools workspaceTools,
                            CastTelemetry telemetry, int maxContextChars, long deadlineMillis,
                            ModelTier quickTier, ModelTier codeTier) {
        this.orchestrator = orchestrator;
        this.workspaceTools = workspaceTools;
        this.telemetry = telemetry;
        this.maxContextChars = maxContextChars;
        this.promptBudgetChars = Math.max(512, (int) (maxContextChars * 0.70));
        this.resultBudgetChars = Math.max(512, Math.min(3_000, maxContextChars - promptBudgetChars - 256));
        this.deadlineMillis = Math.max(1, deadlineMillis);
        this.quickTier = quickTier;
        this.codeTier = codeTier;
    }

    McpTool.ExecutionResult summarizeFiles(JsonNode args) throws Exception {
        String question = optionalText(args, "question", "Summarize the important behavior, responsibilities, and risks.");
        String instructions = """
                You are performing a bounded, read-only repository summary. Use only the supplied files.
                Answer the question directly, cite workspace-relative paths, distinguish facts from inference,
                and keep the response concise.

                Question: %s
                """.formatted(question);
        return delegateSections(instructions, fileSections(paths(args, "paths", true)), Workload.QUICK);
    }

    McpTool.ExecutionResult analyzeFailure(JsonNode args) throws Exception {
        String prompt = """
                Perform first-pass failure triage. Do not claim a final root cause. Return:
                1. the most likely causes in ranked order,
                2. evidence for and against each cause,
                3. the smallest useful verification steps,
                4. relevant file paths when supplied.

                Question:
                %s
                """.formatted(
                optionalText(args, "question", "What most likely failed and what should be checked next?"));
        List<String> sections = new ArrayList<>();
        sections.addAll(splitSection("Failure output", requiredText(args, "log")));
        sections.addAll(fileSections(paths(args, "relevantPaths", false)));
        return delegateSections(prompt, sections, Workload.CODE);
    }

    McpTool.ExecutionResult draftPatch(JsonNode args) throws Exception {
        String prompt = """
                Draft a candidate patch for the requested change using only the supplied files.
                Output a unified diff followed by a short assumptions/verification note. Do not claim the
                patch was applied or tested. Do not modify credentials, authorization policy, or production data.

                Requested change:
                %s
                """.formatted(requiredText(args, "request"));
        return delegateSections(prompt, fileSections(paths(args, "paths", true)), Workload.CODE);
    }

    McpTool.ExecutionResult generateTests(JsonNode args) throws Exception {
        List<String> paths = new ArrayList<>();
        paths.add(requiredText(args, "targetPath"));
        paths.addAll(paths(args, "relatedPaths", false));
        String prompt = """
                Draft focused tests for the requested behavior using the supplied source context.
                Return test code or a unified diff plus a compact edge-case table. Do not claim tests were run.

                Requested behavior:
                %s
                """.formatted(requiredText(args, "request"));
        return delegateSections(prompt, fileSections(paths), Workload.CODE);
    }

    McpTool.ExecutionResult reviewDiff(JsonNode args) throws Exception {
        String prompt = """
                Perform a first-pass, non-security code review of this diff. Report only actionable findings,
                ranked by severity, with file/hunk evidence and a suggested correction. Do not provide final
                correctness approval. If no finding is supported, say so.

                Review focus:
                %s

                """.formatted(optionalText(args, "focus", "Correctness, regressions, edge cases, and maintainability."));
        return delegateSections(prompt, splitSection("Diff", requiredText(args, "diff")), Workload.CODE);
    }

    McpTool.ExecutionResult mapChangeImpact(JsonNode args) throws Exception {
        String symbol = requiredText(args, "symbol");
        int maxResults = Math.min(MAX_SEARCH_RESULTS, Math.max(1, args.path("maxResults").asInt(25)));
        String matches = String.join("\n", workspaceTools.searchWorkspace(symbol, maxResults));
        String prompt = """
                Map the likely impact of changing the named symbol from the literal repository-search results.
                Separate confirmed references from inferred impact, group findings by production code/tests/docs,
                cite file:line evidence, and identify additional searches needed. Do not make edits.

                Symbol: %s
                Question: %s

                Literal search results:
                %s
                """.formatted(symbol,
                optionalText(args, "question", "What would be affected if this symbol or behavior changed?"),
                matches.isBlank() ? "[No literal matches]" : matches);
        return delegateBounded(prompt, Workload.CODE);
    }

    private McpTool.ExecutionResult delegateBounded(String prompt, Workload workload) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
        String bounded = boundedText(prompt, promptBudgetChars);
        Delegated delegated = runPrompt(bounded, workload, deadlineNanos);
        return executionResult(delegated.outcome().answer(), List.of(delegated));
    }

    private McpTool.ExecutionResult delegateSections(String instructions, List<String> sections,
                                                       Workload workload) throws Exception {
        String boundedInstructions = boundedText(instructions, Math.max(256, promptBudgetChars / 3));
        List<String> chunks = packSections(sections, promptBudgetChars - boundedInstructions.length() - 128);
        if (chunks.isEmpty()) chunks = List.of("[No context supplied]");
        if (chunks.size() == 1) {
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
            String prompt = boundedInstructions + "\nContext:\n" + chunks.getFirst();
            Delegated delegated = runPrompt(prompt, workload, deadlineNanos);
            return executionResult(delegated.outcome().answer(), List.of(delegated));
        }

        // Each round trip (one per partition, plus the reducer) gets its own slice of the total
        // deadline rather than sharing one clock: otherwise a slow first partition starves the
        // remaining partitions and the reducer of any time at all.
        long perCallNanos = TimeUnit.MILLISECONDS.toNanos(deadlineMillis) / (chunks.size() + 1);
        List<Delegated> delegations = new ArrayList<>();
        List<String> partials = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            String prompt = boundedInstructions + "\nThis is partition " + (index + 1) + " of " + chunks.size()
                    + ". Return only findings supported by this partition.\nContext:\n" + chunks.get(index);
            long callDeadlineNanos = System.nanoTime() + perCallNanos;
            Delegated delegated = runPrompt(boundedText(prompt, promptBudgetChars), workload, callDeadlineNanos);
            delegations.add(delegated);
            partials.add(boundedText(delegated.outcome().answer(), resultBudgetChars));
        }

        String reducerHeader = "Merge the bounded partial analyses below. Deduplicate findings, preserve path evidence, "
                + "rank actionable results, and mention omitted context. Keep the final answer concise.\n";
        int partialBudget = Math.max(256, (promptBudgetChars - reducerHeader.length()) / partials.size());
        StringBuilder reducer = new StringBuilder(reducerHeader);
        for (int index = 0; index < partials.size(); index++) {
            reducer.append("\n--- PARTIAL ").append(index + 1).append(" ---\n")
                    .append(boundedText(partials.get(index), partialBudget));
        }
        long reducerDeadlineNanos = System.nanoTime() + perCallNanos;
        Delegated merged = runPrompt(boundedText(reducer.toString(), promptBudgetChars), workload, reducerDeadlineNanos);
        delegations.add(merged);
        return executionResult(merged.outcome().answer(), delegations);
    }

    private Delegated runPrompt(String prompt, Workload workload, long deadlineNanos) throws Exception {
        requireWithinLimit(prompt);
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw new DelegationTimeoutException("MCP delegation deadline exceeded");
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        ModelTier requestedTier = workload == Workload.QUICK ? quickTier : codeTier;
        var future = executor.submit(() -> orchestrator.run(
                new TaskRequest(prompt, workload, requestedTier, requestedTier != null)));
        try {
            return new Delegated(prompt, future.get(remaining, TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DelegationTimeoutException("MCP delegation exceeded the " + deadlineMillis + " ms deadline", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception exception) throw exception;
            throw new RuntimeException(e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private McpTool.ExecutionResult executionResult(String answer, List<Delegated> delegations) {
        String boundedAnswer = boundedText(answer, resultBudgetChars);
        HarnessOrchestrator.Outcome last = delegations.getLast().outcome();
        long inputTokens = delegations.stream().mapToLong(item -> item.outcome().inputTokens()).sum();
        long outputTokens = delegations.stream().mapToLong(item -> item.outcome().outputTokens()).sum();
        long durationMs = delegations.stream().mapToLong(item -> item.outcome().durationMs()).sum();
        double cost = delegations.stream().mapToDouble(item -> item.outcome().estimatedCostUsd()).sum();
        int promptChars = delegations.stream().mapToInt(item -> item.prompt().length()).sum();
        String promptHashes = delegations.stream().map(item -> telemetry.promptHash(item.prompt()))
                .reduce("", String::concat);
        return new McpTool.ExecutionResult(boundedAnswer, new McpTool.Delegation(
                last.traceId(), last.provider().id(), last.provider().tier().name(), last.provider().modelName(),
                inputTokens, outputTokens, cost, telemetry.promptHash(promptHashes), promptChars, durationMs),
                boundedAnswer.length() < answer.length());
    }

    private List<String> fileSections(List<String> paths) throws Exception {
        if (paths.isEmpty()) return List.of("[No files supplied]");
        List<String> sections = new ArrayList<>();
        for (String path : new LinkedHashSet<>(paths)) {
            sections.addAll(splitSection("FILE: " + path,
                    workspaceTools.readWorkspaceFile(path, promptBudgetChars * MAX_PARTITIONS)));
        }
        return sections;
    }

    private List<String> splitSection(String label, String text) {
        int chunkSize = Math.max(512, promptBudgetChars / 2);
        List<String> sections = new ArrayList<>();
        for (int offset = 0; offset < text.length(); offset += chunkSize) {
            int end = Math.min(text.length(), offset + chunkSize);
            sections.add("--- " + label + " (chars " + offset + "-" + end + ") ---\n"
                    + text.substring(offset, end));
        }
        if (sections.isEmpty()) sections.add("--- " + label + " ---\n[empty]");
        return sections;
    }

    private List<String> packSections(List<String> sections, int contentBudget) {
        int budget = Math.max(256, contentBudget);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int omitted = 0;
        for (String section : sections) {
            String bounded = boundedText(section, budget);
            if (!current.isEmpty() && current.length() + bounded.length() + 2 > budget) {
                chunks.add(current.toString());
                current.setLength(0);
                if (chunks.size() == MAX_PARTITIONS) {
                    omitted++;
                    continue;
                }
            }
            if (chunks.size() == MAX_PARTITIONS) {
                omitted++;
            } else {
                if (!current.isEmpty()) current.append("\n\n");
                current.append(bounded);
            }
        }
        if (!current.isEmpty() && chunks.size() < MAX_PARTITIONS) chunks.add(current.toString());
        if (omitted > 0 && !chunks.isEmpty()) {
            String marker = "\n[" + omitted + " additional context partitions omitted by budget]";
            int last = chunks.size() - 1;
            chunks.set(last, boundedText(chunks.get(last), Math.max(1, budget - marker.length())) + marker);
        }
        return List.copyOf(chunks);
    }

    private static String boundedText(String text, int maximum) {
        if (text == null || text.length() <= maximum) return text == null ? "" : text;
        String marker = "\n...[bounded content omitted]...\n";
        if (maximum <= marker.length()) return text.substring(0, Math.max(0, maximum));
        int head = (maximum - marker.length()) / 2;
        int tail = maximum - marker.length() - head;
        return text.substring(0, head) + marker + text.substring(text.length() - tail);
    }

    private record Delegated(String prompt, HarnessOrchestrator.Outcome outcome) { }

    private List<String> paths(JsonNode args, String field, boolean required) {
        JsonNode value = args.path(field);
        if (value.isMissingNode() || value.isNull()) {
            if (required) throw new IllegalArgumentException(field + " must contain at least one path");
            return List.of();
        }
        if (!value.isArray()) throw new IllegalArgumentException(field + " must be an array of paths");
        List<String> paths = new ArrayList<>();
        value.forEach(node -> {
            if (!node.isTextual() || node.asText().isBlank()) {
                throw new IllegalArgumentException(field + " must contain only non-blank strings");
            }
            paths.add(node.asText());
        });
        if (required && paths.isEmpty()) throw new IllegalArgumentException(field + " must contain at least one path");
        if (paths.size() > MAX_PATHS) throw new IllegalArgumentException(field + " cannot contain more than " + MAX_PATHS + " paths");
        return paths;
    }

    private static String requiredText(JsonNode args, String field) {
        String value = args.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
    private static String optionalText(JsonNode args, String field, String fallback) {
        String value = args.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private void requireWithinLimit(String text) {
        if (text.length() > promptBudgetChars) {
            throw new IllegalArgumentException("delegation context is " + text.length()
                    + " characters; pre-dispatch prompt budget is " + promptBudgetChars
                    + " of the configured " + maxContextChars + " character context window.");
        }
    }

    @SuppressWarnings("serial")
    private static final class DelegationTimeoutException extends RuntimeException {
        DelegationTimeoutException(String message) { super(message); }
        DelegationTimeoutException(String message, Throwable cause) { super(message, cause); }
    }

}
