package dev.justnels.castcli.reliability;

public enum FailureKind {
    AUTHENTICATION(false), POLICY(false), CONTEXT_LENGTH(false), RATE_LIMIT(true), TIMEOUT(true), TRANSIENT(true), PERMANENT(false);

    private final boolean retryable;
    FailureKind(boolean retryable) { this.retryable = retryable; }
    public boolean retryable() { return retryable; }
}
