package dev.justnels.castcli.model;

import dev.justnels.castcli.config.ProviderConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;

public class ChatModelFactory {
    public ChatModel create(ProviderConfig provider) {
        return OpenAiChatModel.builder()
                .baseUrl(provider.baseUrl())
                .apiKey(provider.resolvedApiKey())
                .modelName(provider.modelName())
                .temperature(provider.temperature())
                .timeout(Duration.ofSeconds(provider.timeoutSeconds()))
                .maxRetries(0)
                .build();
    }

    public StreamingChatModel createStreaming(ProviderConfig provider) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(provider.baseUrl())
                .apiKey(provider.resolvedApiKey())
                .modelName(provider.modelName())
                .temperature(provider.temperature())
                .timeout(Duration.ofSeconds(provider.timeoutSeconds()))
                .build();
    }
}

