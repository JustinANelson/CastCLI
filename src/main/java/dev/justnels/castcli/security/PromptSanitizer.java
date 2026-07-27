package dev.justnels.castcli.security;

import java.util.regex.Pattern;

/**
 * Sanitizes user prompts to mitigate prompt injection, instruction override attacks,
 * dangerous character sequences, and accidental credential leakage before sending queries
 * to LLMs, execution agents, or telemetry pipelines.
 */
public final class PromptSanitizer {

    private static final Pattern SYSTEM_PROMPT_OVERRIDE = Pattern.compile(
            "(?i)(?:ignore\\s+(?:all\\s+)?previous\\s+instructions|system\\s+prompt\\s*:|disregard\\s+(?:the\\s+)?above|repeat\\s+(?:the\\s+)?(?:system|previous)\\s+prompt|output\\s+(?:the\\s+)?system\\s+instructions)"
    );

    private static final Pattern JAILBREAK_MARKERS = Pattern.compile(
            "(?i)(?:DAN\\s+mode|jailbreak|developer\\s+mode\\s+enabled|do\\s+anything\\s+now|act\\s+as\\s+an?\\s+unrestricted|ignore\\s+safety\\s+guidelines)"
    );

    private static final Pattern DANGEROUS_SHELL_SEQUENCES = Pattern.compile(
            "[;&|`$]\\s*(?:rm\\s+-rf|del\\s+/f|shutdown|format\\s+[a-z]:)"
    );

    private static final Pattern SENSITIVE_CREDENTIAL_PATTERNS = Pattern.compile(
            "(?i)(?:sk-[A-Za-z0-9\\-_]{20,}|sk-ant-[A-Za-z0-9\\-_]{20,}|AKIA[0-9A-Z]{16}|Bearer\\s+eyJ[A-Za-z0-9\\-_.]+|-----BEGIN\\s+(?:RSA\\s+)?PRIVATE\\s+KEY-----)"
    );

    private static final Pattern ZERO_WIDTH_CHARS = Pattern.compile(
            "[\\u200B-\\u200D\\uFEFF]"
    );

    private PromptSanitizer() {
    }

    /**
     * Inspects a prompt string and throws an IllegalArgumentException if malicious injection
     * patterns are detected.
     *
     * @param prompt User or task prompt
     * @return the prompt if valid
     * @throws IllegalArgumentException if prompt injection or dangerous commands are detected
     */
    public static String validate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }

        String normalized = ZERO_WIDTH_CHARS.matcher(prompt).replaceAll("");

        if (SYSTEM_PROMPT_OVERRIDE.matcher(normalized).find()) {
            throw new IllegalArgumentException("Security violation: Prompt injection attempt detected (instruction override).");
        }

        if (JAILBREAK_MARKERS.matcher(normalized).find()) {
            throw new IllegalArgumentException("Security violation: Jailbreak pattern detected in prompt.");
        }

        if (DANGEROUS_SHELL_SEQUENCES.matcher(normalized).find()) {
            throw new IllegalArgumentException("Security violation: Dangerous destructive shell sequence detected.");
        }

        return prompt;
    }

    /**
     * Sanitizes a prompt string by stripping instruction override and jailbreak attempt keywords.
     *
     * @param prompt User or task prompt
     * @return sanitized prompt string
     */
    public static String sanitize(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }

        String result = ZERO_WIDTH_CHARS.matcher(prompt).replaceAll("");
        result = SYSTEM_PROMPT_OVERRIDE.matcher(result).replaceAll("[FILTERED_INSTRUCTION_OVERRIDE]");
        result = JAILBREAK_MARKERS.matcher(result).replaceAll("[FILTERED_JAILBREAK]");
        return sanitizeSecrets(result);
    }

    /**
     * Replaces high-entropy credentials, API keys, and sensitive tokens with redaction placeholders.
     *
     * @param input raw text string
     * @return text with secrets masked
     */
    public static String sanitizeSecrets(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        return SENSITIVE_CREDENTIAL_PATTERNS.matcher(input).replaceAll("[REDACTED_SECRET]");
    }
}
