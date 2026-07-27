package dev.justnels.castcli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.justnels.castcli.observability.CastTelemetry;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import dev.justnels.castcli.tools.WorkspaceTools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Builds bounded, read-only task packets for local models. The model may propose code or a diff, but
 * these tools never mutate the workspace or execute a process.
 */
final class ReadOnlyDelegationTools {
    private static final int MAX_PATHS = 20;

    private final HarnessOrchestrator orchestrator;
    private final WorkspaceTools workspaceTools;
    private final CastTelemetry telemetry;
    private final int maxContextChars;

    ReadOnlyDelegationTools(HarnessOrchestrator orchestrator, WorkspaceTools workspaceTools,
                            CastTelemetry telemetry, int maxContextChars) {
        this.orchestrator = orchestrator;
        this.workspaceTools = workspaceTools;
        this.telemetry = telemetry;
        this.maxContextChars = maxContextChars;
    }

    McpTool.ExecutionResult summarizeFiles(JsonNode args) throws Exception {
        String question = optionalText(args, "question", "Summarize the important behavior, responsibilities, and risks.");
        String prompt = """
                You are performing a bounded, read-only repository summary. Use only the supplied files.
                Answer the question directly, cite workspace-relative paths, distinguish facts from inference,
                and keep the response concise.

                Question: %s

                %s
                """.formatted(question, fileContext(paths(args, "paths", true)));
        return delegate(prompt, Workload.QUICK);
    }

    McpTool.ExecutionResult analyzeFailure(JsonNode args) throws Exception {
        String prompt = """
                Perform first-pass failure triage. Do not claim a final root cause. Return:
                1. the most likely causes in ranked order,
                2. evidence for and against each cause,
                3. the smallest useful verification steps,
                4. relevant file paths when supplied.

                Failure output:
                %s

                Question:
                %s

                Relevant files:
                %s
                """.formatted(requiredText(args, "log"),
                optionalText(args, "question", "What most likely failed and what should be checked next?"),
                fileContext(paths(args, "relevantPaths", false)));
        return delegate(prompt, Workload.CODE);
    }

    McpTool.ExecutionResult draftPatch(JsonNode args) throws Exception {
        String prompt = """
                Draft a candidate patch for the requested change using only the supplied files.
                Output a unified diff followed by a short assumptions/verification note. Do not claim the
                patch was applied or tested. Do not modify credentials, authorization policy, or production data.

                Requested change:
                %s

                Files:
                %s
                """.formatted(requiredText(args, "request"), fileContext(paths(args, "paths", true)));
        return delegate(prompt, Workload.CODE);
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

                Source context:
                %s
                """.formatted(requiredText(args, "request"), fileContext(paths));
        return delegate(prompt, Workload.CODE);
    }

    McpTool.ExecutionResult reviewDiff(JsonNode args) {
        String prompt = """
                Perform a first-pass, non-security code review of this diff. Report only actionable findings,
                ranked by severity, with file/hunk evidence and a suggested correction. Do not provide final
                correctness approval. If no finding is supported, say so.

                Review focus:
                %s

                Diff:
                %s
                """.formatted(optionalText(args, "focus", "Correctness, regressions, edge cases, and maintainability."),
                requiredText(args, "diff"));
        return delegate(prompt, Workload.CODE);
    }

    McpTool.ExecutionResult mapChangeImpact(JsonNode args) throws Exception {
        String symbol = requiredText(args, "symbol");
        int maxResults = args.path("maxResults").asInt(100);
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
        return delegate(prompt, Workload.CODE);
    }

    private McpTool.ExecutionResult delegate(String prompt, Workload workload) {
        requireWithinLimit(prompt);
        HarnessOrchestrator.Outcome outcome = orchestrator.run(new TaskRequest(prompt, workload, null));
        return new McpTool.ExecutionResult(outcome.answer(), new McpTool.Delegation(
                outcome.traceId(), outcome.provider().id(), outcome.provider().tier().name(),
                outcome.provider().modelName(), outcome.inputTokens(), outcome.outputTokens(),
                outcome.estimatedCostUsd(), telemetry.promptHash(prompt), prompt.length(), outcome.durationMs()));
    }

    private String fileContext(List<String> paths) throws Exception {
        if (paths.isEmpty()) return "[No files supplied]";
        StringBuilder context = new StringBuilder();
        for (String path : new LinkedHashSet<>(paths)) {
            context.append("\n--- FILE: ").append(path).append(" ---\n")
                    .append(workspaceTools.readWorkspaceFile(path)).append('\n');
            requireWithinLimit(context.toString());
        }
        return context.toString();
    }

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
        if (text.length() > maxContextChars) {
            throw new IllegalArgumentException("delegation context is " + text.length()
                    + " characters; configured maximum is " + maxContextChars
                    + ". Supply fewer/smaller files or a shorter log/diff.");
        }
    }

}
