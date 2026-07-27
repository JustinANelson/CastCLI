package dev.justnels.castcli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.RoutingConfig;
import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class McpStdioServerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void speaksInitializeToolsListAndToolsCallOverStdio() throws Exception {
        Files.writeString(workspace.resolve("Hello.java"), "class Hello {}\n");

        HarnessConfig config = new HarnessConfig(
                List.of(new ProviderConfig("small", ModelTier.SMALL_LOCAL, "http://fake/v1/", "small-model",
                        null, 0.1, 30, true, true)),
                new RoutingConfig(240, true),
                new ToolConfig(workspace.toString(), 100_000, false));

        String requests = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"read_workspace_file\",\"arguments\":{\"path\":\"Hello.java\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"remember_context\",\"arguments\":{\"topic\":\"db-schema\",\"insight\":\"Use Postgres JSONB for flexible tags\",\"author\":\"Claude-Code\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"recall_context\",\"arguments\":{\"query\":\"db-schema\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"nonexistent_tool\",\"arguments\":{}}}") + "\n";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpStdioServer server = new McpStdioServer(config,
                new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));

        server.serve();

        assertThat(workspace.resolve(".cast/index-ignore.json")).exists();

        List<JsonNode> responses = out.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank())
                .map(this::readTree)
                .toList();

        assertThat(responses).hasSize(6); // no response for the notification

        JsonNode initResult = responses.get(0).get("result");
        assertThat(initResult.get("protocolVersion").asText()).isEqualTo("2025-11-25");
        assertThat(initResult.get("serverInfo").get("name").asText()).isEqualTo("cast-cli");

        JsonNode toolsList = responses.get(1).get("result").get("tools");
        List<String> toolNames = toolsList.findValuesAsText("name");
        assertThat(toolNames).contains("ask_local", "summarize_files", "analyze_failure", "draft_patch",
                "generate_tests", "review_diff", "map_change_impact", "list_models", "read_workspace_file",
                "list_workspace_files", "search_workspace", "remember_context", "recall_context");

        JsonNode askLocalSchema = toolsList.findValues("inputSchema").get(toolNames.indexOf("ask_local"));
        assertThat(askLocalSchema.path("required").toString()).contains("\"prompt\"");
        assertThat(askLocalSchema.path("properties").path("workload").path("enum").toString())
                .isEqualTo("[\"AUTO\",\"QUICK\",\"CODE\",\"REASONING\"]");
        JsonNode summarizeSchema = toolsList.findValues("inputSchema").get(toolNames.indexOf("summarize_files"));
        assertThat(summarizeSchema.path("properties").path("paths").path("items").path("type").asText())
                .isEqualTo("string");
        JsonNode readResult = responses.get(2).get("result");
        assertThat(readResult.get("content").get(0).get("text").asText()).contains("class Hello {}");
        assertThat(readResult.path("_meta").path("castcli/invocationId").asText()).isNotBlank();
        assertThat(readResult.path("_meta").path("castcli/usageAuditPath").asText())
                .endsWith("mcp-usage.jsonl");

        JsonNode rememberResult = responses.get(3).get("result");
        assertThat(rememberResult.get("content").get(0).get("text").asText()).contains("Memory recorded successfully");

        JsonNode recallResult = responses.get(4).get("result");
        assertThat(recallResult.get("content").get(0).get("text").asText()).contains("db-schema", "Postgres JSONB");

        JsonNode unknownToolResult = responses.get(5).get("result");
        assertThat(unknownToolResult.get("isError").asBoolean()).isTrue();

        Path auditPath = workspace.resolve(".cast/metrics/mcp-usage.jsonl");
        List<McpUsageRecord> usage = new McpUsageStore(auditPath).readSince(0);
        assertThat(usage).hasSize(4);
        assertThat(usage).extracting(McpUsageRecord::toolName)
                .containsExactly("read_workspace_file", "remember_context", "recall_context", "nonexistent_tool");
        assertThat(usage.getLast().success()).isFalse();
    }

    @Test
    void askLocalReturnsDelegationReceiptAndPersistsModelUsage() throws Exception {
        ProviderConfig provider = new ProviderConfig("small", ModelTier.SMALL_LOCAL, "http://fake/v1/",
                "small-model", null, 0.1, 30, true, true);
        HarnessConfig config = new HarnessConfig(List.of(provider), new RoutingConfig(240, true),
                new ToolConfig(workspace.toString(), 100_000, false));
        HarnessOrchestrator fakeOrchestrator = new HarnessOrchestrator(config) {
            @Override public Outcome run(dev.justnels.castcli.orchestration.TaskRequest task) {
                return new Outcome(provider, "delegated answer", List.of(), List.of(), 25, false,
                        40, 12, 0.0001, "0123456789abcdef0123456789abcdef");
            }
        };
        String request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ask_local","arguments":{"prompt":"summarize this","workload":"QUICK"}}}
                """;
        request = handshake() + request;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new McpStdioServer(config, new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), fakeOrchestrator).serve();

        JsonNode result = out.toString(StandardCharsets.UTF_8).lines()
                .map(this::readTree).toList().getLast().path("result");

        assertThat(result.path("content").get(0).path("text").asText())
                .contains("delegated answer", "CastCLI delegation", "small/small-model", "52 local tokens");
        assertThat(result.path("_meta").path("castcli/provider").asText()).isEqualTo("small");
        assertThat(result.path("_meta").path("castcli/inputTokens").asLong()).isEqualTo(40);
        McpUsageRecord usage = new McpUsageStore(workspace.resolve(".cast/metrics/mcp-usage.jsonl"))
                .readSince(0).getFirst();
        assertThat(usage.delegated()).isTrue();
        assertThat(usage.totalTokens()).isEqualTo(52);
        assertThat(usage.traceId()).isEqualTo("0123456789abcdef0123456789abcdef");
    }

    @Test
    void structuredDelegationBuildsBoundedContextAndInvalidWorkloadFails() throws Exception {
        Files.writeString(workspace.resolve("Hello.java"), "class Hello { int value() { return 1; } }\n");
        ProviderConfig provider = new ProviderConfig("small", ModelTier.SMALL_LOCAL, "http://fake/v1/",
                "small-model", null, 0.1, 30, true, true);
        HarnessConfig config = new HarnessConfig(List.of(provider), new RoutingConfig(240, true),
                new ToolConfig(workspace.toString(), 100_000, false));
        AtomicReference<TaskRequest> captured = new AtomicReference<>();
        HarnessOrchestrator fakeOrchestrator = new HarnessOrchestrator(config) {
            @Override public Outcome run(TaskRequest task) {
                captured.set(task);
                return new Outcome(provider, "structured answer", List.of(), List.of(), 10, false,
                        20, 5, 0, "fedcba9876543210fedcba9876543210");
            }
        };
        String requests = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"summarize_files\",\"arguments\":{\"paths\":[\"Hello.java\"],\"question\":\"What does value return?\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ask_local\",\"arguments\":{\"prompt\":\"hello\",\"workload\":\"ANALYSIS\"}}}") + "\n";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new McpStdioServer(config, new ByteArrayInputStream(
                (handshake() + requests).getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), fakeOrchestrator).serve();
        List<JsonNode> responses = out.toString(StandardCharsets.UTF_8).lines().map(this::readTree).toList();

        JsonNode summary = responses.get(1).path("result");
        assertThat(summary.path("content").get(0).path("text").asText())
                .contains("structured answer", "CastCLI delegation");
        assertThat(summary.path("_meta").path("castcli/provider").asText()).isEqualTo("small");
        assertThat(captured.get().workload()).isEqualTo(Workload.QUICK);
        assertThat(captured.get().prompt()).contains("Hello.java", "return 1", "What does value return?");

        JsonNode invalid = responses.get(2).path("result");
        assertThat(invalid.path("isError").asBoolean()).isTrue();
        assertThat(invalid.path("content").get(0).path("text").asText())
                .contains("AUTO, QUICK, CODE, or REASONING");

        List<McpUsageRecord> usage = new McpUsageStore(workspace.resolve(".cast/metrics/mcp-usage.jsonl"))
                .readSince(0);
        assertThat(usage).extracting(McpUsageRecord::toolName)
                .containsExactly("summarize_files", "ask_local");
        assertThat(usage.getFirst().delegated()).isTrue();
        assertThat(usage.getLast().delegationAttempted()).isTrue();
        assertThat(usage.getLast().success()).isFalse();
    }

    @Test
    void rejectsMalformedAndPreInitializationRequests() throws Exception {
        ProviderConfig provider = new ProviderConfig("small", ModelTier.SMALL_LOCAL, "http://fake/v1/",
                "small-model", null, 0.1, 30, true, true);
        HarnessConfig config = new HarnessConfig(List.of(provider), new RoutingConfig(240, true),
                new ToolConfig(workspace.toString(), 100_000, false));
        String requests = """
                {not-json
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        new McpStdioServer(config, new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8)).serve();

        List<JsonNode> responses = out.toString(StandardCharsets.UTF_8).lines().map(this::readTree).toList();
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).path("error").path("code").asInt()).isEqualTo(-32700);
        assertThat(responses.get(1).path("error").path("code").asInt()).isEqualTo(-32002);
    }

    private JsonNode readTree(String line) {
        try {
            return mapper.readTree(line);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String handshake() {
        return """
                {"jsonrpc":"2.0","id":100,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """;
    }
}
