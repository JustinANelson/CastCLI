package dev.justnels.castcli.validation;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Outcome-aware execution cascade engine (R-101) orchestrating:
 * <pre>
 * local attempt -> deterministic validation -> targeted retry with diagnostic -> cloud escalation
 * </pre>
 */
public final class ExecutionCascadeEngine {

    public record CascadeResult(
            boolean success,
            String finalAnswer,
            int localAttempts,
            boolean escalatedToCloud,
            List<ValidationResult> validationHistory,
            long totalInputTokens,
            long totalOutputTokens,
            double totalCostUsd) {}

    public CascadeResult execute(
            HarnessConfig config,
            TaskRequest initialRequest,
            List<ValidationContract> validators,
            Function<TaskRequest, HarnessOrchestrator.Outcome> modelExecutor) {

        int maxLocalRetries = 2;
        Path workspaceRoot = Path.of(config.tools().workspaceRoot()).toAbsolutePath().normalize();

        List<ValidationResult> history = new ArrayList<>();
        long totalInput = 0;
        long totalOutput = 0;
        double totalCost = 0.0;
        int localAttempts = 0;

        TaskRequest currentRequest = initialRequest;
        HarnessOrchestrator.Outcome lastOutcome = null;

        // Step 1 & 2: Local attempts with targeted retries
        for (int attempt = 1; attempt <= maxLocalRetries + 1; attempt++) {
            localAttempts++;
            lastOutcome = modelExecutor.apply(currentRequest);
            if (lastOutcome != null) {
                totalInput += lastOutcome.inputTokens();
                totalOutput += lastOutcome.outputTokens();
                totalCost += lastOutcome.estimatedCostUsd();
            }

            String text = lastOutcome != null ? lastOutcome.answer() : "";
            ValidationResult failure = runValidators(validators, text, workspaceRoot, history);
            if (failure == null) {
                return new CascadeResult(true, text, localAttempts, false, history, totalInput, totalOutput, totalCost);
            }

            if (attempt <= maxLocalRetries) {
                String retryPrompt = initialRequest.prompt() + "\n\n[VALIDATION FAILURE]: " + failure.diagnostic()
                        + "\n[RETRY HINT]: " + failure.retryPromptHint();
                currentRequest = new TaskRequest(retryPrompt, initialRequest.workload(), initialRequest.requestedTier(),
                        initialRequest.strict(), initialRequest.toolsDisabled());
            }
        }

        // Step 3: Cloud Escalation if local attempts fail and policy permits cloud
        boolean cloudEnabled = config.providers().stream()
                .anyMatch(p -> p.tier() == ModelTier.FRONTIER_CLOUD && p.enabled() && p.credentialsAvailable());

        if (cloudEnabled) {
            String cloudPrompt = initialRequest.prompt() + "\n\n[PREVIOUS LOCAL ATTEMPTS FAILED VALIDATION]: "
                    + (history.isEmpty() ? "validation failed" : history.get(history.size() - 1).diagnostic());
            TaskRequest cloudRequest = new TaskRequest(cloudPrompt, initialRequest.workload(), ModelTier.FRONTIER_CLOUD,
                    false, initialRequest.toolsDisabled());

            HarnessOrchestrator.Outcome cloudOutcome = modelExecutor.apply(cloudRequest);
            if (cloudOutcome != null) {
                totalInput += cloudOutcome.inputTokens();
                totalOutput += cloudOutcome.outputTokens();
                totalCost += cloudOutcome.estimatedCostUsd();
            }

            String cloudText = cloudOutcome != null ? cloudOutcome.answer() : "";
            ValidationResult cloudFailure = runValidators(validators, cloudText, workspaceRoot, history);
            boolean success = cloudFailure == null;
            return new CascadeResult(success, cloudText, localAttempts, true, history, totalInput, totalOutput, totalCost);
        }

        String finalText = lastOutcome != null ? lastOutcome.answer() : "";
        return new CascadeResult(false, finalText, localAttempts, false, history, totalInput, totalOutput, totalCost);
    }

    private static ValidationResult runValidators(
            List<ValidationContract> validators, String output, Path workspaceRoot, List<ValidationResult> history) {
        if (validators == null || validators.isEmpty()) {
            return null;
        }
        for (ValidationContract validator : validators) {
            ValidationResult res = validator.validate(output, workspaceRoot);
            history.add(res);
            if (!res.isPass()) {
                return res;
            }
        }
        return null;
    }
}
