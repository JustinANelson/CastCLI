package dev.justnels.castcli.reliability;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

public final class FailureClassifier {
    public FailureKind classify(Throwable failure) {
        Throwable root = root(failure);
        if (root instanceof TimeoutException || root instanceof SocketTimeoutException) return FailureKind.TIMEOUT;
        if (root instanceof ConnectException) return FailureKind.TRANSIENT;
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(' ').append(current.getMessage());
            if (current.getCause() == current) break;
        }
        String message = messages.toString().toLowerCase(Locale.ROOT);
        if (contains(message, "401", "403", "unauthorized", "authentication", "api key")) return FailureKind.AUTHENTICATION;
        if (contains(message, "429", "rate limit", "too many requests")) return FailureKind.RATE_LIMIT;
        if (contains(message, "context length", "context window", "maximum context", "too many tokens")) return FailureKind.CONTEXT_LENGTH;
        if (contains(message, "policy", "content filter", "safety")) return FailureKind.POLICY;
        if (contains(message, "timeout", "timed out", "connection reset", "temporarily unavailable", "500", "502", "503", "504", "server_error", "server failure", "internal server error")) return FailureKind.TRANSIENT;
        return FailureKind.PERMANENT;
    }

    private static Throwable root(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static boolean contains(String message, String... values) {
        for (String value : values) if (message.contains(value)) return true;
        return false;
    }
}
