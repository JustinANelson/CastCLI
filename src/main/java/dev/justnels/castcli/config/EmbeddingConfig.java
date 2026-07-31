package dev.justnels.castcli.config;

import dev.justnels.castcli.security.SecretResolver;

import java.util.List;

/**
 * Configures the optional semantic (embedding-based) workspace search feature: {@code llm-harness index}
 * builds a persisted vector index of the workspace using this provider, and {@code SemanticSearchTools}
 * queries it. Disabled by default -- a config that omits this block entirely gets {@link #disabled()}.
 */
public record EmbeddingConfig(
        boolean enabled,
        String baseUrl,
        String modelName,
        String apiKeyEnv,
        int timeoutSeconds,
        int chunkLines,
        int chunkOverlapLines,
        long maxFileBytes,
        List<String> includeGlobs,
        List<String> excludeGlobs,
        String indexPath,
        double costPerMillionInputTokens,
        int maxConcurrency,
        int maxBatchSize,
        int maxRetries) {

    public static final List<String> DEFAULT_INCLUDE_GLOBS = List.of(
            "**/*.java", "**/*.kt", "**/*.kts", "**/*.py", "**/*.js", "**/*.jsx", "**/*.ts", "**/*.tsx",
            "**/*.go", "**/*.rs", "**/*.c", "**/*.h", "**/*.cpp", "**/*.hpp", "**/*.cs", "**/*.proto",
            "**/*.md", "**/*.json", "**/*.yml", "**/*.yaml", "**/*.gradle", "**/*.gradle.kts");

    public static final List<String> DEFAULT_EXCLUDE_GLOBS = List.of(
            "**/.git/**", "**/.gitignore", "**/build/**", "**/.gradle/**", "**/.harness/**", "**/.cast/**", "**/node_modules/**",
            "**/dist/**", "**/target/**", "**/.idea/**", "**/.vs/**",
            "**/credentials.json", "**/credentials*.json", "**/credential*.json",
            "**/*secret*", "**/*password*", "**/*token*",
            "**/*.pem", "**/*.key", "**/*.crt", "**/*.cer", "**/*.p12", "**/*.pfx", "**/*.asc", "**/*.jks", "**/*.keystore",
            "**/.env", "**/.env.*", "**/*.env",
            "**/id_rsa*", "**/id_ed25519*", "**/id_dsa*", "**/id_ecdsa*", "**/*.ppk",
            "**/shadow", "**/passwd", "**/htpasswd", "**/auth.json", "**/netrc", "**/.netrc");

    public static final String DEFAULT_INDEX_PATH = ".cast/index/workspace-embeddings.json";
    public static final int DEFAULT_MAX_CONCURRENCY = 8;
    public static final int DEFAULT_MAX_BATCH_SIZE = 16;
    public static final int DEFAULT_MAX_RETRIES = 1;
    private static final int DEFAULT_CHUNK_LINES = 60;
    private static final int DEFAULT_CHUNK_OVERLAP_LINES = 10;
    private static final long DEFAULT_MAX_FILE_BYTES = 300_000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    public EmbeddingConfig(
            boolean enabled,
            String baseUrl,
            String modelName,
            String apiKeyEnv,
            int timeoutSeconds,
            int chunkLines,
            int chunkOverlapLines,
            long maxFileBytes,
            List<String> includeGlobs,
            List<String> excludeGlobs,
            String indexPath,
            double costPerMillionInputTokens) {
        this(enabled, baseUrl, modelName, apiKeyEnv, timeoutSeconds, chunkLines, chunkOverlapLines,
                maxFileBytes, includeGlobs, excludeGlobs, indexPath, costPerMillionInputTokens,
                DEFAULT_MAX_CONCURRENCY, DEFAULT_MAX_BATCH_SIZE, DEFAULT_MAX_RETRIES);
    }

    public EmbeddingConfig(
            boolean enabled,
            String baseUrl,
            String modelName,
            String apiKeyEnv,
            int timeoutSeconds,
            int chunkLines,
            int chunkOverlapLines,
            long maxFileBytes,
            List<String> includeGlobs,
            List<String> excludeGlobs,
            String indexPath,
            double costPerMillionInputTokens,
            int maxConcurrency) {
        this(enabled, baseUrl, modelName, apiKeyEnv, timeoutSeconds, chunkLines, chunkOverlapLines,
                maxFileBytes, includeGlobs, excludeGlobs, indexPath, costPerMillionInputTokens,
                maxConcurrency, DEFAULT_MAX_BATCH_SIZE, DEFAULT_MAX_RETRIES);
    }

    public EmbeddingConfig {
        if (enabled) {
            requireText(baseUrl, "embeddings baseUrl");
            requireText(modelName, "embeddings modelName");
        }
        if (timeoutSeconds < 1) {
            timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
        if (chunkLines < 1) {
            chunkLines = DEFAULT_CHUNK_LINES;
        }
        if (chunkOverlapLines < 0 || chunkOverlapLines >= chunkLines) {
            chunkOverlapLines = Math.min(DEFAULT_CHUNK_OVERLAP_LINES, chunkLines - 1);
        }
        if (maxFileBytes < 1) {
            maxFileBytes = DEFAULT_MAX_FILE_BYTES;
        }
        if (maxConcurrency < 1) {
            maxConcurrency = DEFAULT_MAX_CONCURRENCY;
        }
        if (maxBatchSize < 1) {
            maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
        }
        if (maxRetries < 0) {
            maxRetries = DEFAULT_MAX_RETRIES;
        }
        includeGlobs = (includeGlobs == null || includeGlobs.isEmpty()) ? DEFAULT_INCLUDE_GLOBS : List.copyOf(includeGlobs);
        excludeGlobs = (excludeGlobs == null || excludeGlobs.isEmpty()) ? DEFAULT_EXCLUDE_GLOBS : List.copyOf(excludeGlobs);
        indexPath = (indexPath == null || indexPath.isBlank()) ? DEFAULT_INDEX_PATH : indexPath;
        if (costPerMillionInputTokens < 0) {
            throw new IllegalArgumentException("costPerMillionInputTokens must not be negative");
        }
    }

    public static EmbeddingConfig disabled() {
        return new EmbeddingConfig(false, null, null, null, DEFAULT_TIMEOUT_SECONDS, DEFAULT_CHUNK_LINES,
                DEFAULT_CHUNK_OVERLAP_LINES, DEFAULT_MAX_FILE_BYTES, null, null, null, 0.0,
                DEFAULT_MAX_CONCURRENCY, DEFAULT_MAX_BATCH_SIZE, DEFAULT_MAX_RETRIES);
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

    /** Estimated USD cost of embedding {@code inputTokens}; embedding responses have no output tokens. */
    public double estimatedCostUsd(long inputTokens) {
        return (inputTokens / 1_000_000.0) * costPerMillionInputTokens;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}

