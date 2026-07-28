package dev.justnels.castcli.orchestration;

import dev.justnels.castcli.config.EmbeddingConfig;
import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.RoutingConfig;
import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.model.ChatModelFactory;
import dev.justnels.castcli.tools.AutoApprovalGate;
import dev.justnels.castcli.tools.DefaultToolSelector;
import dev.justnels.castcli.tools.FastPathExecutor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HarnessOrchestratorTest {
    private final HarnessConfig config = new HarnessConfig(
            List.of(
                    new ProviderConfig("small", ModelTier.SMALL_LOCAL, "http://localhost/v1/", "small-model", null, 0.1, 30, true, true),
                    new ProviderConfig("large", ModelTier.LARGE_LOCAL, "http://localhost/v1/", "large-model", null, 0.1, 30, true, true)
            ),
            new RoutingConfig(240, true),
            new ToolConfig(".", 100000, false)
    );

    @Test
    void fastPathBypassesModelFactory() {
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(config);
        TaskRequest task = new TaskRequest("what time is it", Workload.AUTO, null);

        HarnessOrchestrator.Outcome outcome = orchestrator.run(task);

        assertThat(outcome.fastPath()).isTrue();
        assertThat(outcome.provider().id()).isEqualTo("fast-path");
        assertThat(outcome.toolsUsed()).contains("currentTime");
        assertThat(outcome.answer()).contains("Current UTC time is");
    }

    @Test
    void attachesSemanticSearchToolWhenEmbeddingsEnabledAndWorkspaceToolsSelected() {
        HarnessConfig withEmbeddings = new HarnessConfig(
                config.providers(), config.routing(), config.tools(), List.of(),
                new EmbeddingConfig(true, "http://fake/v1/", "fake-embed", null, 30, 60, 10, 300_000,
                        null, null, null, 0.0));
        FakeChatModelFactory factory = new FakeChatModelFactory();
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(
                withEmbeddings, factory, new DefaultToolSelector(), new FastPathExecutor(),
                AutoApprovalGate.INSTANCE, null);

        HarnessOrchestrator.Outcome outcome = orchestrator.run(
                new TaskRequest("search the repository for the ticket validation code", Workload.CODE, null));

        assertThat(outcome.toolsSelected()).contains("WorkspaceTools", "SemanticSearchTools");
    }

    @Test
    void omitsSemanticSearchToolWhenEmbeddingsDisabled() {
        FakeChatModelFactory factory = new FakeChatModelFactory();
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(
                config, factory, new DefaultToolSelector(), new FastPathExecutor(),
                AutoApprovalGate.INSTANCE, null);

        HarnessOrchestrator.Outcome outcome = orchestrator.run(
                new TaskRequest("search the repository for the ticket validation code", Workload.CODE, null));

        assertThat(outcome.toolsSelected()).contains("WorkspaceTools");
        assertThat(outcome.toolsSelected()).doesNotContain("SemanticSearchTools");
    }

    @Test
    void toolBearingFailureIsNotRetriedOrFallenBackToAnotherProvider() {
        List<String> calls = new CopyOnWriteArrayList<>();
        ChatModelFactory failingFactory = new ChatModelFactory() {
            @Override public ChatModel create(ProviderConfig provider) {
                return new ChatModel() {
                    @Override public ChatResponse doChat(ChatRequest request) {
                        calls.add(provider.id());
                        throw new IllegalStateException("503 temporarily unavailable");
                    }
                };
            }
        };
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(config, failingFactory,
                new DefaultToolSelector(), new FastPathExecutor(), AutoApprovalGate.INSTANCE, null);

        assertThatThrownBy(() -> orchestrator.run(new TaskRequest(
                "search the repository for this class", Workload.CODE, null))).isInstanceOf(RuntimeException.class);
        assertThat(calls).containsExactly("large");
    }

    @Test
    void toolBearingRequestPrependsFlattenedHistoryInsteadOfDroppingIt() {
        List<String> capturedMessageText = new CopyOnWriteArrayList<>();
        ChatModelFactory factory = new ChatModelFactory() {
            @Override public ChatModel create(ProviderConfig provider) {
                return new ChatModel() {
                    @Override public ChatResponse doChat(ChatRequest request) {
                        request.messages().forEach(m -> capturedMessageText.add(m.toString()));
                        return ChatResponse.builder().aiMessage(AiMessage.from("no tools needed, here's the answer")).build();
                    }
                };
            }
        };
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(config, factory,
                new DefaultToolSelector(), new FastPathExecutor(), AutoApprovalGate.INSTANCE, null);
        List<ChatMessage> history = List.of(SystemMessage.from("remember the ticket ID is T-42"));

        orchestrator.run(new TaskRequest("search the repository for the ticket validation code", Workload.CODE, null), history);

        assertThat(capturedMessageText).anySatisfy(text -> assertThat(text).contains("T-42"));
    }

    @Test
    void redactsSecretsLeakedInModelOutputBeforeReturningTheOutcome() {
        ChatModelFactory leakyFactory = new ChatModelFactory() {
            @Override public ChatModel create(ProviderConfig provider) {
                return new ChatModel() {
                    @Override public ChatResponse doChat(ChatRequest request) {
                        return ChatResponse.builder()
                                .aiMessage(AiMessage.from("Here is the key you asked for: AKIAIOSFODNN7EXAMPLE"))
                                .build();
                    }
                };
            }
        };
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(config, leakyFactory,
                new DefaultToolSelector(), new FastPathExecutor(), AutoApprovalGate.INSTANCE, null);

        HarnessOrchestrator.Outcome outcome = orchestrator.run(new TaskRequest("tell me a joke", Workload.AUTO, null));

        assertThat(outcome.answer()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(outcome.answer()).contains("[REDACTED_AWS_KEY]");
    }

    private static final class FakeChatModelFactory extends ChatModelFactory {
        @Override
        public ChatModel create(ProviderConfig provider) {
            return new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest chatRequest) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("no tools needed, here's the answer")).build();
                }
            };
        }
    }
}

