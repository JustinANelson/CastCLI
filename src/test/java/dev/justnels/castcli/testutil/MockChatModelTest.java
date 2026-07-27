package dev.justnels.castcli.testutil;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockChatModelTest {

    @Test
    void returnsFixedResponse() {
        MockChatModel model = new MockChatModel("Hello from mock");
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("Hi"))
                .build();

        ChatResponse response = model.chat(request);

        assertThat(response.aiMessage().text()).isEqualTo("Hello from mock");
        assertThat(model.getInvocationCount()).isEqualTo(1);
    }

    @Test
    void throwsConfiguredException() {
        RuntimeException failure = new RuntimeException("HTTP 429 Too Many Requests");
        MockChatModel model = new MockChatModel(failure);
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("Hi"))
                .build();

        assertThatThrownBy(() -> model.chat(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("429");
        assertThat(model.getInvocationCount()).isEqualTo(1);
    }
}
