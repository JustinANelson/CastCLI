package dev.justnels.castcli.observability;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for sanitizing sensitive credentials, API keys, tokens, and authorization
 * data from logs and OpenTelemetry span attributes.
 */
public final class SecretRedactor {

    private static final Pattern API_KEY_PATTERN = Pattern.compile(
            "(?i)(sk-[a-zA-Z0-9_-]{20,}|gsk_[a-zA-Z0-9_-]{20,}|key-[a-zA-Z0-9_-]{20,}|ai_key_[a-zA-Z0-9_-]{20,})"
    );

    private static final Pattern HEADER_SECRET_PATTERN = Pattern.compile(
            "(?i)(api[-_]?key|auth(?:orization)?|token|secret|password)\\s*[:=]\\s*[\"']?(?!\\[)([^\"'\\r\\n;,]+)"
    );

    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
            "-----BEGIN (?:RSA|EC|OPENSSH|PRIVATE) KEY-----([\\s\\S]*?)-----END (?:RSA|EC|OPENSSH|PRIVATE) KEY-----"
    );

    private SecretRedactor() {
    }

    /**
     * Sanitizes sensitive credentials and secrets in text, replacing them with [REDACTED].
     *
     * @param input raw text string
     * @return sanitized string with secrets redacted
     */
    public static String redact(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        String result = input;
        result = PRIVATE_KEY_PATTERN.matcher(result).replaceAll("[REDACTED_PRIVATE_KEY]");
        result = API_KEY_PATTERN.matcher(result).replaceAll("[REDACTED_KEY]");

        Matcher headerMatcher = HEADER_SECRET_PATTERN.matcher(result);
        if (headerMatcher.find()) {
            result = headerMatcher.replaceAll(match -> match.group(1) + ": [REDACTED]");
        }

        return result;
    }
}
