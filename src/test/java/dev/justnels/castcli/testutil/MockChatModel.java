package dev.justnels.castcli.testutil;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic test double for LangChain4j ChatModel, allowing simulation
 * of success responses, API errors (HTTP 429 / 500), rate limiting, and call counts.
 */
public class MockChatModel implements ChatModel {

    private final String fixedResponse;
    private final RuntimeException exceptionToThrow;
    private final AtomicInteger invocationCount = new AtomicInteger(0);

    public MockChatModel(String fixedResponse) {
        this.fixedResponse = fixedResponse;
        this.exceptionToThrow = null;
    }

    public MockChatModel(RuntimeException exceptionToThrow) {
        this.fixedResponse = null;
        this.exceptionToThrow = exceptionToThrow;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        invocationCount.incrementAndGet();
        if (exceptionToThrow != null) {
            throw exceptionToThrow;
        }
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(fixedResponse != null ? fixedResponse : "Mock response"))
                .build();
    }

    public int getInvocationCount() {
        return invocationCount.get();
    }
}
