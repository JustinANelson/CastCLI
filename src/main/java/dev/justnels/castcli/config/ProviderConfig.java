package dev.justnels.castcli.config;

import dev.justnels.castcli.security.SecretResolver;

import java.util.Objects;

public record ProviderConfig(
        String id,
        ModelTier tier,
        String baseUrl,
        String modelName,
        String apiKeyEnv,
        double temperature,
        int timeoutSeconds,
        boolean toolsEnabled,
        boolean enabled,
        Integer maxToolsSupported,
        double costPerMillionInputTokens,
        double costPerMillionOutputTokens,
        Integer maxOutputTokens) {

    public ProviderConfig {
        requireText(id, "provider id");
        Objects.requireNonNull(tier, "provider tier");
        requireText(baseUrl, "provider baseUrl");
        requireText(modelName, "provider modelName");
        if (temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        if (costPerMillionInputTokens < 0 || costPerMillionOutputTokens < 0) {
            throw new IllegalArgumentException("cost per million tokens must not be negative");
        }
        if (maxOutputTokens != null && maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens must be positive when configured");
        }
    }

    public ProviderConfig(
            String id,
            ModelTier tier,
            String baseUrl,
            String modelName,
            String apiKeyEnv,
            double temperature,
            int timeoutSeconds,
            boolean toolsEnabled,
            boolean enabled,
            Integer maxToolsSupported,
            double costPerMillionInputTokens,
            double costPerMillionOutputTokens) {
        this(id, tier, baseUrl, modelName, apiKeyEnv, temperature, timeoutSeconds, toolsEnabled, enabled,
                maxToolsSupported, costPerMillionInputTokens, costPerMillionOutputTokens, null);
    }

    public ProviderConfig(
            String id,
            ModelTier tier,
            String baseUrl,
            String modelName,
            String apiKeyEnv,
            double temperature,
            int timeoutSeconds,
            boolean toolsEnabled,
            boolean enabled,
            Integer maxToolsSupported) {
        this(id, tier, baseUrl, modelName, apiKeyEnv, temperature, timeoutSeconds, toolsEnabled, enabled,
                maxToolsSupported, 0.0, 0.0, null);
    }

    public ProviderConfig(
            String id,
            ModelTier tier,
            String baseUrl,
            String modelName,
            String apiKeyEnv,
            double temperature,
            int timeoutSeconds,
            boolean toolsEnabled,
            boolean enabled) {
        this(id, tier, baseUrl, modelName, apiKeyEnv, temperature, timeoutSeconds, toolsEnabled, enabled, null);
    }

    /** Estimated USD cost for the given token counts, using this provider's configured per-million-token rates. */
    public double estimatedCostUsd(long inputTokens, long outputTokens) {
        return (inputTokens / 1_000_000.0) * costPerMillionInputTokens
                + (outputTokens / 1_000_000.0) * costPerMillionOutputTokens;
    }

    public int effectiveMaxToolsSupported() {
        if (maxToolsSupported != null) {
            return maxToolsSupported;
        }
        return toolsEnabled ? 10 : 0;
    }

    public ProviderConfig withMaxOutputTokens(int limit) {
        return new ProviderConfig(id, tier, baseUrl, modelName, apiKeyEnv, temperature, timeoutSeconds,
                toolsEnabled, enabled, maxToolsSupported, costPerMillionInputTokens,
                costPerMillionOutputTokens, limit);
    }

    public boolean credentialsAvailable() {
        return apiKeyEnv == null || apiKeyEnv.isBlank()
                || SecretResolver.defaultResolver().resolve(apiKeyEnv).isPresent();
    }

    public String resolvedApiKey() {
        if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
            return "local-not-required";
        }
        return SecretResolver.defaultResolver().resolve(apiKeyEnv)
                .orElseThrow(() -> new IllegalStateException("Missing API key environment variable: " + apiKeyEnv));
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}

