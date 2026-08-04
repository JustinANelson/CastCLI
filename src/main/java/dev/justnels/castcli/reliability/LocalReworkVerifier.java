package dev.justnels.castcli.reliability;

import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Executes tasks on local model tiers with an automated self-correcting verification loop.
 * Re-prompts the local model with explicit verification feedback upon failure.
 */
public final class LocalReworkVerifier {

    public record VerificationResult(boolean success, String feedback) {
        public static VerificationResult ok() {
            return new VerificationResult(true, null);
        }
        public static VerificationResult error(String feedback) {
            return new VerificationResult(false, feedback == null ? "Verification failed." : feedback.trim());
        }
    }

    private final HarnessOrchestrator orchestrator;
    private final int maxAttempts;

    public LocalReworkVerifier(HarnessOrchestrator orchestrator) {
        this(orchestrator, 2);
    }

    public LocalReworkVerifier(HarnessOrchestrator orchestrator, int maxAttempts) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator must not be null");
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /** Runs task with a simple predicate verifier on the output. */
    public HarnessOrchestrator.Outcome runWithPredicate(TaskRequest task, Predicate<String> simpleVerifier) {
        return runWithFeedback(task, answer -> simpleVerifier.test(answer)
                ? VerificationResult.ok()
                : VerificationResult.error("Output did not pass local verification rules."));
    }

    /** Runs task with a detailed verification function returning feedback. */
    public HarnessOrchestrator.Outcome runWithFeedback(TaskRequest task, Function<String, VerificationResult> verifier) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(verifier, "verifier must not be null");

        String currentPrompt = task.prompt();
        HarnessOrchestrator.Outcome lastOutcome = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            TaskRequest currentRequest = new TaskRequest(
                    currentPrompt,
                    task.workload(),
                    task.requestedProviderId() == null
                            ? (task.requestedTier() == null ? ModelTier.LARGE_LOCAL : task.requestedTier())
                            : null,
                    task.requestedProviderId(),
                    task.strict(),
                    task.toolsDisabled());

            lastOutcome = orchestrator.run(currentRequest);
            String answer = lastOutcome != null ? lastOutcome.answer() : null;

            VerificationResult result = verifier.apply(answer);
            if (result != null && result.success()) {
                return lastOutcome;
            }

            if (attempt < maxAttempts && result != null && result.feedback() != null) {
                currentPrompt = task.prompt()
                        + "\n\nPRIOR ATTEMPT OUTPUT:\n" + (answer == null ? "" : answer)
                        + "\n\nVERIFICATION FAILURE FEEDBACK:\n" + result.feedback()
                        + "\n\nPlease correct the output to resolve the feedback.";
            }
        }

        return lastOutcome;
    }
}
