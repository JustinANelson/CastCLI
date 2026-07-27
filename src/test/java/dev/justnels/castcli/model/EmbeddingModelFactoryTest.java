package dev.justnels.castcli.model;

import dev.justnels.castcli.config.EmbeddingConfig;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingModelFactoryTest {

    private final EmbeddingModelFactory factory = new EmbeddingModelFactory();

    private static EmbeddingConfig localConfig(String apiKeyEnv) {
        return new EmbeddingConfig(
                true, "http://localhost:11434/v1/", "qwen3-embedding:0.6b", apiKeyEnv,
                30, 60, 10, 300_000, null, null, null, 0.0);
    }

    @Test
    void createBuildsAnEmbeddingModelWithTheConfiguredModelName() {
        EmbeddingModel model = factory.create(localConfig(null));

        assertThat(model).isNotNull();
        assertThat(((dev.langchain4j.model.openai.OpenAiEmbeddingModel) model).modelName())
                .isEqualTo("qwen3-embedding:0.6b");
    }

    @Test
    void createFailsFastWhenApiKeyEnvIsSetButNotExported() {
        EmbeddingConfig config = localConfig("CAST_CLI_TEST_UNSET_API_KEY_ENV_VAR");

        assertThatThrownBy(() -> factory.create(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAST_CLI_TEST_UNSET_API_KEY_ENV_VAR");
    }
}
