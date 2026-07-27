package dev.justnels.castcli.model;

import dev.justnels.castcli.config.EmbeddingConfig;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.time.Duration;

public class EmbeddingModelFactory {
    public EmbeddingModel create(EmbeddingConfig config) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.resolvedApiKey())
                .modelName(config.modelName())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
    }
}

