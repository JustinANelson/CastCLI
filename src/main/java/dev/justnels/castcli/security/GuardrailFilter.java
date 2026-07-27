package dev.justnels.castcli.security;

import java.util.regex.Pattern;

/**
 * Filter for sanitizing LLM outputs, tool responses, and logged messages to prevent
 * leaks of PII (Personally Identifiable Information) and enterprise sensitive tokens.
 */
public final class GuardrailFilter {

    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile(
            "\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|6(?:011|5[0-9]{2})[0-9]{12})\\b"
    );

    private static final Pattern SSN_PATTERN = Pattern.compile(
            "\\b(?!000|666|9\\d{2})\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}\\b"
    );

    private static final Pattern JWT_TOKEN_PATTERN = Pattern.compile(
            "\\beyJ[a-zA-Z0-9_-]{10,}\\.eyJ[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{10,}\\b"
    );

    private static final Pattern AWS_KEY_PATTERN = Pattern.compile(
            "\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"
    );

    private static final Pattern GITHUB_TOKEN_PATTERN = Pattern.compile(
            "(?i)(?:ghp_[a-zA-Z0-9]{20,}|github_pat_[a-zA-Z0-9_]{20,})"
    );

    private static final Pattern SLACK_TOKEN_PATTERN = Pattern.compile(
            "\\bxox[baprs]-[0-9a-zA-Z]{10,48}\\b"
    );

    private GuardrailFilter() {
    }

    /**
     * Filters and redacts sensitive data (PII, credentials, access keys, tokens) from text.
     *
     * @param text input text string
     * @return text with sensitive information redacted
     */
    public static String filter(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String result = text;

        // Specific DLP PII and token passes first for precise labeling
        result = CREDIT_CARD_PATTERN.matcher(result).replaceAll("[REDACTED_CREDIT_CARD]");
        result = SSN_PATTERN.matcher(result).replaceAll("[REDACTED_SSN]");
        result = JWT_TOKEN_PATTERN.matcher(result).replaceAll("[REDACTED_JWT_TOKEN]");
        result = AWS_KEY_PATTERN.matcher(result).replaceAll("[REDACTED_AWS_KEY]");
        result = GITHUB_TOKEN_PATTERN.matcher(result).replaceAll("[REDACTED_GITHUB_TOKEN]");
        result = SLACK_TOKEN_PATTERN.matcher(result).replaceAll("[REDACTED_SLACK_TOKEN]");

        // Deliberately does NOT also apply observability.SecretRedactor here: that redactor's
        // broad "key/token/secret/password: <anything>" pattern is tuned for logs and telemetry
        // metadata, where over-redaction is free. Applied to a coding harness's user-facing
        // answers (which routinely contain code with variables/fields named apiKey, token, etc.)
        // it would corrupt legitimate output. Use SecretRedactor directly for logging paths.
        return result;
    }
}
