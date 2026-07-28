package dev.justnels.castcli.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientToolSupportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parsesNestedToolParameterSchema() {
        JsonNode tools = json("""
                [{"type":"function","function":{"name":"get_weather","description":"Look up weather",
                  "parameters":{"type":"object","properties":{
                    "city":{"type":"string","description":"City name"},
                    "unit":{"type":"string","enum":["celsius","fahrenheit"]},
                    "forecast":{"type":"array","items":{"type":"string"}}
                  },"required":["city"]}}}]
                """);

        List<ToolSpecification> specs = ClientToolSupport.parseToolSpecifications(tools);

        assertThat(specs).hasSize(1);
        ToolSpecification spec = specs.getFirst();
        assertThat(spec.name()).isEqualTo("get_weather");
        assertThat(spec.description()).isEqualTo("Look up weather");
        assertThat(spec.parameters().properties().keySet()).containsExactlyInAnyOrder("city", "unit", "forecast");
        assertThat(spec.parameters().required()).containsExactly("city");
    }

    @Test
    void parsesToolWithNoParameters() {
        JsonNode tools = json("""
                [{"type":"function","function":{"name":"ping"}}]
                """);
        List<ToolSpecification> specs = ClientToolSupport.parseToolSpecifications(tools);
        assertThat(specs).hasSize(1);
        assertThat(specs.getFirst().parameters().properties()).isEmpty();
    }

    @Test
    void rejectsUnsupportedToolType() {
        JsonNode tools = json("""
                [{"type":"code_interpreter"}]
                """);
        assertThatThrownBy(() -> ClientToolSupport.parseToolSpecifications(tools))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code_interpreter");
    }

    @Test
    void rejectsToolWithoutName() {
        JsonNode tools = json("""
                [{"type":"function","function":{}}]
                """);
        assertThatThrownBy(() -> ClientToolSupport.parseToolSpecifications(tools))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<ToolSpecification> sampleTools() {
        return ClientToolSupport.parseToolSpecifications(json("""
                [{"type":"function","function":{"name":"get_weather","parameters":{"type":"object","properties":{}}}},
                 {"type":"function","function":{"name":"get_time","parameters":{"type":"object","properties":{}}}}]
                """));
    }

    @Test
    void resolvesAutoRequiredAndNoneToolChoice() {
        List<ToolSpecification> tools = sampleTools();
        assertThat(ClientToolSupport.resolveToolChoice(json("\"auto\""), tools).toolChoice()).isEqualTo(ToolChoice.AUTO);
        assertThat(ClientToolSupport.resolveToolChoice(json("\"required\""), tools).toolChoice()).isEqualTo(ToolChoice.REQUIRED);
        assertThat(ClientToolSupport.resolveToolChoice(json("\"none\""), tools).toolChoice()).isEqualTo(ToolChoice.NONE);
    }

    @Test
    void resolvesMissingToolChoiceAsNullMeaningProviderDefault() {
        var resolution = ClientToolSupport.resolveToolChoice(json("{}").path("tool_choice"), sampleTools());
        assertThat(resolution.toolChoice()).isNull();
        assertThat(resolution.tools()).hasSize(2);
    }

    @Test
    void forcedNamedToolChoiceNarrowsToolListAndRequiresCall() {
        List<ToolSpecification> tools = sampleTools();
        var resolution = ClientToolSupport.resolveToolChoice(json("""
                {"type":"function","function":{"name":"get_time"}}
                """), tools);
        assertThat(resolution.toolChoice()).isEqualTo(ToolChoice.REQUIRED);
        assertThat(resolution.tools()).extracting(ToolSpecification::name).containsExactly("get_time");
    }

    @Test
    void rejectsForcedToolChoiceNamingUnknownTool() {
        List<ToolSpecification> tools = sampleTools();
        assertThatThrownBy(() -> ClientToolSupport.resolveToolChoice(json("""
                {"type":"function","function":{"name":"nonexistent"}}
                """), tools)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedToolChoiceValue() {
        assertThatThrownBy(() -> ClientToolSupport.resolveToolChoice(json("\"weird\""), sampleTools()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reconstructsFullConversationIncludingToolRoundTrip() {
        JsonNode messages = json("""
                [
                  {"role":"system","content":"be terse"},
                  {"role":"user","content":"weather in Paris?"},
                  {"role":"assistant","content":null,"tool_calls":[
                    {"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\\"city\\":\\"Paris\\"}"}}
                  ]},
                  {"role":"tool","tool_call_id":"call_1","content":"18C, cloudy"},
                  {"role":"assistant","content":"It is 18C and cloudy in Paris."}
                ]
                """);

        List<ChatMessage> chatMessages = ClientToolSupport.toChatMessages(messages);

        assertThat(chatMessages).hasSize(5);
        assertThat(chatMessages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(chatMessages.get(1)).isInstanceOf(UserMessage.class);

        AiMessage toolCallMessage = (AiMessage) chatMessages.get(2);
        assertThat(toolCallMessage.hasToolExecutionRequests()).isTrue();
        assertThat(toolCallMessage.toolExecutionRequests()).hasSize(1);
        assertThat(toolCallMessage.toolExecutionRequests().getFirst().id()).isEqualTo("call_1");
        assertThat(toolCallMessage.toolExecutionRequests().getFirst().name()).isEqualTo("get_weather");
        assertThat(toolCallMessage.toolExecutionRequests().getFirst().arguments()).isEqualTo("{\"city\":\"Paris\"}");

        ToolExecutionResultMessage resultMessage = (ToolExecutionResultMessage) chatMessages.get(3);
        assertThat(resultMessage.id()).isEqualTo("call_1");
        assertThat(resultMessage.text()).isEqualTo("18C, cloudy");

        AiMessage finalMessage = (AiMessage) chatMessages.get(4);
        assertThat(finalMessage.text()).isEqualTo("It is 18C and cloudy in Paris.");
    }

    @Test
    void rejectsToolResultMessageWithoutToolCallId() {
        JsonNode messages = json("""
                [{"role":"tool","content":"result"}]
                """);
        assertThatThrownBy(() -> ClientToolSupport.toChatMessages(messages))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedRole() {
        JsonNode messages = json("""
                [{"role":"developer","content":"x"}]
                """);
        assertThatThrownBy(() -> ClientToolSupport.toChatMessages(messages))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyMessages() {
        assertThatThrownBy(() -> ClientToolSupport.toChatMessages(json("[]")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void endsWithUserMessageDetectsTrailingUserRole() {
        assertThat(ClientToolSupport.endsWithUserMessage(json("""
                [{"role":"system","content":"x"},{"role":"user","content":"y"}]
                """))).isTrue();
        assertThat(ClientToolSupport.endsWithUserMessage(json("""
                [{"role":"user","content":"y"},{"role":"assistant","content":"z"}]
                """))).isFalse();
        assertThat(ClientToolSupport.endsWithUserMessage(json("[]"))).isFalse();
    }

    @Test
    void splitLastUserTurnSeparatesHistoryFromCurrentTurn() {
        JsonNode messages = json("""
                [
                  {"role":"system","content":"be brief"},
                  {"role":"user","content":"first"},
                  {"role":"assistant","content":"first reply"},
                  {"role":"user","content":"second"}
                ]
                """);

        ClientToolSupport.ConversationSplit split = ClientToolSupport.splitLastUserTurn(messages);

        assertThat(split.currentUserText()).isEqualTo("second");
        assertThat(split.history()).hasSize(3);
        assertThat(split.history().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(split.history().get(1)).isInstanceOf(UserMessage.class);
        assertThat(split.history().get(2)).isInstanceOf(AiMessage.class);
    }

    @Test
    void splitLastUserTurnOnSingleUserMessageYieldsEmptyHistory() {
        ClientToolSupport.ConversationSplit split = ClientToolSupport.splitLastUserTurn(json("""
                [{"role":"user","content":"hi"}]
                """));
        assertThat(split.history()).isEmpty();
        assertThat(split.currentUserText()).isEqualTo("hi");
    }

    @Test
    void splitLastUserTurnRejectsConversationNotEndingInUser() {
        JsonNode messages = json("""
                [{"role":"user","content":"hi"},{"role":"assistant","content":"hello"}]
                """);
        assertThatThrownBy(() -> ClientToolSupport.splitLastUserTurn(messages))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
