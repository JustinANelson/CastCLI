package dev.justnels.castcli.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class EmbeddingConfigTest {
    @Test
    void disabledIsTheDefaultAndRequiresNothing() {
        EmbeddingConfig config = EmbeddingConfig.disabled();

        assertThat(config.enabled()).isFalse();
        assertThat(catchThrowable(() -> new EmbeddingConfig(
                false, null, null, null, 0, 0, 0, 0, null, null, null, 0.0)))
                .isNull();
    }

    @Test
    void enabledRequiresBaseUrlAndModelName() {
        assertThatThrownBy(() -> new EmbeddingConfig(
                true, null, "model", null, 30, 10, 2, 1000, null, null, null, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");

        assertThatThrownBy(() -> new EmbeddingConfig(
                true, "http://localhost/v1/", null, null, 30, 10, 2, 1000, null, null, null, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelName");
    }

    @Test
    void appliesDefaultsForOmittedFields() {
        EmbeddingConfig config = new EmbeddingConfig(
                true, "http://localhost:11434/v1/", "nomic-embed-text", null, 0, 0, 0, 0, null, null, null, 0.0);

        assertThat(config.timeoutSeconds()).isPositive();
        assertThat(config.chunkLines()).isPositive();
        assertThat(config.chunkOverlapLines()).isBetween(0, config.chunkLines() - 1);
        assertThat(config.maxFileBytes()).isPositive();
        assertThat(config.maxConcurrency()).isEqualTo(EmbeddingConfig.DEFAULT_MAX_CONCURRENCY);
        assertThat(config.maxBatchSize()).isEqualTo(EmbeddingConfig.DEFAULT_MAX_BATCH_SIZE);
        assertThat(config.maxRetries()).isEqualTo(EmbeddingConfig.DEFAULT_MAX_RETRIES);
        assertThat(config.includeGlobs()).isEqualTo(EmbeddingConfig.DEFAULT_INCLUDE_GLOBS);
        assertThat(config.excludeGlobs()).isEqualTo(EmbeddingConfig.DEFAULT_EXCLUDE_GLOBS);
        assertThat(config.indexPath()).isEqualTo(EmbeddingConfig.DEFAULT_INDEX_PATH);
    }

    @Test
    void honorsExplicitMaxConcurrencyAndBatchingAndRetries() {
        EmbeddingConfig config = new EmbeddingConfig(
                true, "http://localhost/v1/", "m", null, 30, 10, 2, 1000, null, null, null, 0.0, 4, 32, 2);

        assertThat(config.maxConcurrency()).isEqualTo(4);
        assertThat(config.maxBatchSize()).isEqualTo(32);
        assertThat(config.maxRetries()).isEqualTo(2);
    }

    @Test
    void rejectsNegativeCost() {
        assertThatThrownBy(() -> new EmbeddingConfig(
                true, "http://localhost/v1/", "m", null, 30, 10, 2, 1000, null, null, null, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void honorsExplicitIncludeAndExcludeGlobs() {
        EmbeddingConfig config = new EmbeddingConfig(
                true, "http://localhost/v1/", "m", null, 30, 10, 2, 1000,
                List.of("**/*.cs"), List.of("**/obj/**"), null, 0.0);

        assertThat(config.includeGlobs()).containsExactly("**/*.cs");
        assertThat(config.excludeGlobs()).containsExactly("**/obj/**");
    }

    @Test
    void resolvedApiKeyDoesNotRequireEnvVarWhenApiKeyEnvIsNull() {
        EmbeddingConfig config = new EmbeddingConfig(
                true, "http://localhost/v1/", "m", null, 30, 10, 2, 1000, null, null, null, 0.0);

        assertThat(config.credentialsAvailable()).isTrue();
        assertThat(config.resolvedApiKey()).isEqualTo("local-not-required");
    }

    @Test
    void estimatesCostFromInputTokensOnly() {
        EmbeddingConfig config = new EmbeddingConfig(
                true, "http://localhost/v1/", "m", null, 30, 10, 2, 1000, null, null, null, 2.0);

        assertThat(config.estimatedCostUsd(1_000_000)).isEqualTo(2.0);
    }
}

