package dev.justnels.castcli.validation;

/**
 * Result of executing a deterministic validation contract on model output.
 */
public record ValidationResult(
        Status status,
        String validatorName,
        String diagnostic,
        String retryPromptHint,
        long durationMs) {

    public enum Status {
        PASS,
        FAIL,
        INDETERMINATE,
        POLICY_BLOCKED
    }

    public static ValidationResult pass(String validatorName, long durationMs) {
        return new ValidationResult(Status.PASS, validatorName, "Validation passed cleanly", null, durationMs);
    }

    public static ValidationResult fail(String validatorName, String diagnostic, String retryPromptHint, long durationMs) {
        return new ValidationResult(Status.FAIL, validatorName, diagnostic, retryPromptHint, durationMs);
    }

    public static ValidationResult indeterminate(String validatorName, String reason, long durationMs) {
        return new ValidationResult(Status.INDETERMINATE, validatorName, reason, null, durationMs);
    }

    public static ValidationResult policyBlocked(String validatorName, String reason, long durationMs) {
        return new ValidationResult(Status.POLICY_BLOCKED, validatorName, reason, null, durationMs);
    }

    public boolean isPass() {
        return status == Status.PASS;
    }
}
