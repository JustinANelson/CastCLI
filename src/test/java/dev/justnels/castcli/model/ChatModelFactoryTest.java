package dev.justnels.castcli.model;

import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatModelFactoryTest {

    private final ChatModelFactory factory = new ChatModelFactory();

    private static ProviderConfig localProvider() {
        return new ProviderConfig(
                "coder-local", ModelTier.SMALL_LOCAL, "http://localhost:11434/v1/", "qwen3.5:9b",
                null, 0.2, 30, true, true);
    }

    @Test
    void createBuildsAChatModelWithTheProvidersModelNameAndTemperature() {
        ChatModel model = factory.create(localProvider());

        assertThat(model.defaultRequestParameters().modelName()).isEqualTo("qwen3.5:9b");
        assertThat(model.defaultRequestParameters().temperature()).isEqualTo(0.2);
    }

    @Test
    void createStreamingBuildsAStreamingModelWithTheProvidersModelNameAndTemperature() {
        StreamingChatModel model = factory.createStreaming(localProvider());

        assertThat(model.defaultRequestParameters().modelName()).isEqualTo("qwen3.5:9b");
        assertThat(model.defaultRequestParameters().temperature()).isEqualTo(0.2);
    }

    @Test
    void createDoesNotRequireAnApiKeyWhenApiKeyEnvIsNull() {
        assertThat(factory.create(localProvider())).isNotNull();
    }

    @Test
    void createFailsFastWhenApiKeyEnvIsSetButNotExported() {
        ProviderConfig cloudProvider = new ProviderConfig(
                "cloud", ModelTier.FRONTIER_CLOUD, "https://api.openai.com/v1/", "gpt-4o",
                "CAST_CLI_TEST_UNSET_API_KEY_ENV_VAR", 0.2, 30, true, true);

        assertThatThrownBy(() -> factory.create(cloudProvider))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAST_CLI_TEST_UNSET_API_KEY_ENV_VAR");
    }
}
