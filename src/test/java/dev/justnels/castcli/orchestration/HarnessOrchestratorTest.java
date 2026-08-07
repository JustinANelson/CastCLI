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
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
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
    void toolsDisabledSkipsToolSelectionEvenWhenPromptContainsToolMarkers() {
        FakeChatModelFactory factory = new FakeChatModelFactory();
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(
                config, factory, new DefaultToolSelector(), new FastPathExecutor(),
                AutoApprovalGate.INSTANCE, null);

        // "search the repository" would normally select WorkspaceTools (see the sibling test above);
        // toolsDisabled must suppress that regardless of prompt content, e.g. for report/summary
        // generation calls that only quote prior output and have no legitimate tool use.
        HarnessOrchestrator.Outcome outcome = orchestrator.run(
                new TaskRequest("search the repository for the ticket validation code", Workload.CODE, null, false, true));

        assertThat(outcome.toolsSelected()).isEmpty();
    }

    @Test
    void boundsInternalAndWorkerModelOutput() {
        List<Integer> limits = new CopyOnWriteArrayList<>();
        ChatModelFactory factory = new ChatModelFactory() {
            @Override public ChatModel create(ProviderConfig provider) {
                limits.add(provider.maxOutputTokens());
                return new ChatModel() {
                    @Override public ChatResponse doChat(ChatRequest request) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("done")).build();
                    }
                };
            }
        };
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(config, factory,
                new DefaultToolSelector(), new FastPathExecutor(), AutoApprovalGate.INSTANCE, null);

        orchestrator.run(new TaskRequest("Produce a concise internal plan", Workload.REASONING,
                ModelTier.LARGE_LOCAL, true, true));
        orchestrator.run(new TaskRequest("Create and write an implementation", Workload.CODE, null));

        assertThat(limits).containsExactly(768, 2_048);
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

    @Test
    void executesTextFormLocalToolCallsAndContinuesUntilFinalAnswer(@TempDir Path root) throws Exception {
        Queue<String> responses = new ArrayDeque<>(List.of(
                """
                        {"toolCalls":[
                          {"name":"writeWorkspaceFile","arguments":{"path":"index.html","content":"<h1>Chat</h1>"}},
                          {"name":"writeWorkspaceFile","arguments":{"path":"app.js","content":"console.log('ready')"}}
                        ]}
                        """.strip(),
                "Created the requested chat interface."));
        AtomicInteger calls = new AtomicInteger();
        List<ChatRequest> requests = new CopyOnWriteArrayList<>();
        ChatModelFactory factory = textResponseFactory(responses, calls, requests);
        HarnessConfig writable = new HarnessConfig(config.providers(), config.routing(),
                new ToolConfig(root.toString(), 100_000, false, true, false, true));
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(writable, factory,
                new DefaultToolSelector(), new FastPathExecutor(), AutoApprovalGate.INSTANCE, null);

        HarnessOrchestrator.Outcome outcome = orchestrator.run(
                new TaskRequest("Create and write the interface files", Workload.CODE, null));

        assertThat(Files.readString(root.resolve("index.html"))).isEqualTo("<h1>Chat</h1>");
        assertThat(Files.readString(root.resolve("app.js"))).isEqualTo("console.log('ready')");
        assertThat(outcome.answer()).isEqualTo("Created the requested chat interface.");
        assertThat(outcome.toolsUsed()).containsExactly("writeWorkspaceFile", "writeWorkspaceFile");
        assertThat(calls).hasValue(2);
        assertThat(requests.get(1).messages()).extracting(message -> message.getClass().getSimpleName())
                .containsExactly("UserMessage", "AiMessage", "UserMessage");
    }

    @Test
    void doesNotExecuteJsonEmbeddedInOrdinaryModelProse(@TempDir Path root) {
        String prose = "Example only: {\"name\":\"writeWorkspaceFile\",\"arguments\":"
                + "{\"path\":\"wrong.txt\",\"content\":\"wrong\"}}";
        ChatModelFactory factory = textResponseFactory(new ArrayDeque<>(List.of(prose)), new AtomicInteger());
        HarnessConfig writable = new HarnessConfig(config.providers(), config.routing(),
                new ToolConfig(root.toString(), 100_000, false, true, false, true));
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(writable, factory,
                new DefaultToolSelector(), new FastPathExecutor(), AutoApprovalGate.INSTANCE, null);

        HarnessOrchestrator.Outcome outcome = orchestrator.run(
                new TaskRequest("Create and write the interface files", Workload.CODE, null));

        assertThat(root.resolve("wrong.txt")).doesNotExist();
        assertThat(outcome.answer()).isEqualTo(prose);
        assertThat(outcome.toolsUsed()).isEmpty();
    }

    @Test
    void continuesNativeToolWorkerWhenCappedRoundHasNoFinalText(@TempDir Path root) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChatModelFactory factory = new ChatModelFactory() {
            @Override public ChatModel create(ProviderConfig provider) {
                return new ChatModel() {
                    @Override public ChatResponse doChat(ChatRequest request) {
                        int call = calls.incrementAndGet();
                        return switch (call) {
                            case 1 -> toolResponse("call-a", "a.txt", "alpha");
                            case 2 -> ChatResponse.builder().aiMessage(AiMessage.from("")).build();
                            case 3 -> toolResponse("call-b", "b.txt", "beta");
                            default -> ChatResponse.builder().aiMessage(AiMessage.from("All files created.")).build();
                        };
                    }
                };
            }
        };
        HarnessConfig writable = new HarnessConfig(config.providers(), config.routing(),
                new ToolConfig(root.toString(), 100_000, false, true, false, true));
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(writable, factory,
                new DefaultToolSelector(), new FastPathExecutor(), AutoApprovalGate.INSTANCE, null);

        HarnessOrchestrator.Outcome outcome = orchestrator.run(
                new TaskRequest("Create and write both files", Workload.CODE, null));

        assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo("alpha");
        assertThat(Files.readString(root.resolve("b.txt"))).isEqualTo("beta");
        assertThat(outcome.answer()).isEqualTo("All files created.");
        assertThat(outcome.toolsUsed()).containsExactly("writeWorkspaceFile", "writeWorkspaceFile");
        assertThat(calls).hasValue(4);
    }

    private static ChatResponse toolResponse(String id, String path, String content) {
        ToolExecutionRequest request = ToolExecutionRequest.builder().id(id).name("writeWorkspaceFile")
                .arguments("{\"path\":\"" + path + "\",\"content\":\"" + content + "\"}").build();
        return ChatResponse.builder().aiMessage(AiMessage.from(request)).build();
    }

    private static ChatModelFactory textResponseFactory(Queue<String> responses, AtomicInteger calls) {
        return textResponseFactory(responses, calls, new CopyOnWriteArrayList<>());
    }

    private static ChatModelFactory textResponseFactory(
            Queue<String> responses, AtomicInteger calls, List<ChatRequest> requests) {
        return new ChatModelFactory() {
            @Override public ChatModel create(ProviderConfig provider) {
                return new ChatModel() {
                    @Override public ChatResponse doChat(ChatRequest request) {
                        calls.incrementAndGet();
                        requests.add(request);
                        return ChatResponse.builder().aiMessage(AiMessage.from(responses.remove())).build();
                    }
                };
            }
        };
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

