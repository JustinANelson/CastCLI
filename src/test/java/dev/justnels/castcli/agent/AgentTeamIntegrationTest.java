package dev.justnels.castcli.agent;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.RoutingConfig;
import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.model.ChatModelFactory;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TokenUsageReport;
import dev.justnels.castcli.tools.AutoApprovalGate;
import dev.justnels.castcli.tools.DefaultToolSelector;
import dev.justnels.castcli.tools.FastPathExecutor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Exercises the real {@link AgentTeam} + {@link HarnessOrchestrator} pipeline end to end against a
 * scripted fake {@link ChatModel} (no mocked orchestrator, no network) to verify behavior that a
 * fully-mocked orchestrator test cannot: PM plan parsing, parallel wave execution, the reviewer
 * reject -> rework -> re-review loop, token/cost aggregation, and checkpoint persistence.
 * Context-budget truncation itself is covered separately in {@link AgentTeamContextBuildingTest}.
 */
class AgentTeamIntegrationTest {
    @TempDir
    Path checkpointDir;

    private final CyclicBarrier parallelWaveBarrier = new CyclicBarrier(2);

    @Test
    void runsPlanExecutesInParallelReworksOnRejectionAndPersistsCheckpoint() throws Exception {
        HarnessConfig config = new HarnessConfig(
                List.of(
                        new ProviderConfig("small", ModelTier.SMALL_LOCAL, "http://fake/v1/", "small-model", null,
                                0.1, 30, true, true, null, 1.0, 2.0),
                        new ProviderConfig("large", ModelTier.LARGE_LOCAL, "http://fake/v1/", "large-model", null,
                                0.1, 30, true, true, null, 1.0, 2.0),
                        new ProviderConfig("cloud", ModelTier.FRONTIER_CLOUD, "http://fake/v1/", "cloud-model", null,
                                0.1, 30, true, true, null, 1.0, 2.0)),
                new RoutingConfig(240, true, 500, 1),
                new ToolConfig(".", 100_000, false));

        FakeChatModelFactory factory = new FakeChatModelFactory(AgentTeamIntegrationTest::respond, this::maybeSynchronizeParallelCoders);
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(
                config, factory, new DefaultToolSelector(), new FastPathExecutor(), AutoApprovalGate.INSTANCE, null);
        AgentTeam team = new AgentTeam(config, orchestrator, new CheckpointStore(checkpointDir));

        CommissioningResult result = team.commission("Build feature X");

        assertThat(result.completedTasks()).hasSize(3);
        SubTask featureA = findById(result.completedTasks(), 1);
        SubTask featureB = findById(result.completedTasks(), 2);
        SubTask review = findById(result.completedTasks(), 3);

        assertThat(featureA.output()).contains("Reworked Feature A output");
        assertThat(featureB.output()).contains("Reworked Feature B output");
        assertThat(review.output()).contains("VERDICT: APPROVED");
        assertThat(result.commissioningSummary()).contains("Commissioning approved");

        assertThat(result.totalInputTokens()).isGreaterThan(0);
        assertThat(result.totalOutputTokens()).isGreaterThan(0);
        assertThat(result.estimatedCostUsd()).isGreaterThan(0);

        // Every call in this scripted run returns 10 input / 5 output tokens. Routing config has
        // preferLocal=true, and both the PM's plan/report calls and REASONING-workload reviewer calls
        // are now non-strict requests routed like everything else -- with preferLocal=true they land on
        // LARGE_LOCAL rather than being forced onto FRONTIER_CLOUD. So all 8 calls (plan, 2 initial
        // coders, reject-review, 2 reworked coders, approve-review, report) run on "large" and count as
        // offloaded; "cloud" never runs anything but stays configured as the cost-avoidance reference.
        assertThat(result.referenceFrontierModel()).isEqualTo("cloud-model");
        assertThat(result.tokensOffloadedToLocal()).isEqualTo(8 * (10 + 5));
        assertThat(result.estimatedFrontierCostAvoidedUsd())
                .isCloseTo(8 * ((10 / 1_000_000.0) * 1.0 + (5 / 1_000_000.0) * 2.0), within(1e-9));

        assertThat(result.checkpointPath()).exists();
        Checkpoint persisted = new CheckpointStore(checkpointDir).load(result.checkpointPath()).orElseThrow();
        assertThat(persisted.completedTasks()).hasSize(3);

        // Per-provider breakdown: "large" ran all 8 calls (plan, both review passes, report, and all 4
        // coder calls); "cloud" and "small" never ran anything, so both are absent entirely rather than
        // showing up as zeroed-out rows.
        var tokenUsage = result.tokenUsageByProvider();
        assertThat(tokenUsage.byProvider()).extracting(TokenUsageReport.ProviderUsage::providerId)
                .containsExactly("large");
        var largeUsage = tokenUsage.byProvider().stream().filter(p -> p.providerId().equals("large")).findFirst().orElseThrow();
        assertThat(largeUsage.calls()).isEqualTo(8);
        assertThat(largeUsage.totalTokens()).isEqualTo(8 * (10 + 5));
        assertThat(tokenUsage.cloudTokens()).isZero();
        assertThat(tokenUsage.localTokens()).isEqualTo(8 * (10 + 5));
        assertThat(tokenUsage.cloudShare()).isCloseTo(0.0, within(1e-9));
        assertThat(tokenUsage.localMinusCloudTokens()).isEqualTo(8 * (10 + 5));
    }

    private void maybeSynchronizeParallelCoders(String prompt) {
        boolean isInitialCoderCall = (prompt.contains("Implement feature A logic.") || prompt.contains("Implement feature B logic."))
                && !prompt.contains("REVIEWER FEEDBACK");
        if (!isInitialCoderCall) {
            return;
        }
        try {
            // Only succeeds if both CODER subtasks reach this point concurrently, proving the wave
            // actually ran in parallel rather than one worker finishing before the other started.
            parallelWaveBarrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("Expected CODER subtasks to run concurrently in the same wave", e);
        }
    }

    private static SubTask findById(List<SubTask> tasks, int id) {
        return tasks.stream().filter(t -> t.id() == id).findFirst()
                .orElseThrow(() -> new AssertionError("No subtask with id " + id));
    }

    private static String respond(String prompt) {
        if (prompt.contains("Break down the following goal")) {
            return """
                    TITLE: Implement Feature A
                    ROLE: CODER
                    PROMPT: Implement feature A logic.

                    TITLE: Implement Feature B
                    ROLE: CODER
                    PROMPT: Implement feature B logic.

                    TITLE: Review Implementation
                    ROLE: REVIEWER
                    PROMPT: Review both features for correctness.
                    """;
        }
        if (prompt.contains("Lead Architect and Commissioning Agent")) {
            return "Commissioning approved. Both features are implemented and reviewed.";
        }
        if (prompt.contains("Review both features")) {
            boolean bothReworked = prompt.contains("Reworked Feature A output") && prompt.contains("Reworked Feature B output");
            return bothReworked
                    ? "Both features now look correct.\nVERDICT: APPROVED"
                    : "Feature A is missing input validation.\nVERDICT: REJECTED: missing input validation";
        }
        if (prompt.contains("Implement feature A logic.")) {
            return prompt.contains("REVIEWER FEEDBACK") ? "Reworked Feature A output" : "Feature A initial output";
        }
        if (prompt.contains("Implement feature B logic.")) {
            return prompt.contains("REVIEWER FEEDBACK") ? "Reworked Feature B output" : "Feature B initial output";
        }
        return "Unhandled prompt: " + prompt;
    }

    private static final class FakeChatModelFactory extends ChatModelFactory {
        private final Function<String, String> responder;
        private final java.util.function.Consumer<String> beforeRespond;

        FakeChatModelFactory(Function<String, String> responder, java.util.function.Consumer<String> beforeRespond) {
            this.responder = responder;
            this.beforeRespond = beforeRespond;
        }

        @Override
        public ChatModel create(ProviderConfig provider) {
            return new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest chatRequest) {
                    String prompt = chatRequest.messages().stream()
                            .filter(UserMessage.class::isInstance)
                            .map(UserMessage.class::cast)
                            .map(UserMessage::singleText)
                            .findFirst()
                            .orElse("");
                    beforeRespond.accept(prompt);
                    String answer = responder.apply(prompt);
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(answer))
                            .tokenUsage(new TokenUsage(10, 5))
                            .build();
                }
            };
        }
    }
}

