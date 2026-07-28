package dev.justnels.castcli;

import dev.justnels.castcli.agent.AgentTeam;
import dev.justnels.castcli.agent.CommissioningResult;
import dev.justnels.castcli.agent.SubTask;
import dev.justnels.castcli.config.ConfigLoader;
import dev.justnels.castcli.config.ConfigValidator;
import dev.justnels.castcli.config.HarnessConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.index.WorkspaceEmbeddingIndex;
import dev.justnels.castcli.evaluation.RoutingEvaluationReport;
import dev.justnels.castcli.evaluation.RoutingEvaluator;
import dev.justnels.castcli.memory.MemoryDraft;
import dev.justnels.castcli.memory.MemoryEntry;
import dev.justnels.castcli.memory.MemoryQuery;
import dev.justnels.castcli.memory.MemoryStore;
import dev.justnels.castcli.memory.SqliteMemoryStore;
import dev.justnels.castcli.mcp.McpClientManager;
import dev.justnels.castcli.mcp.McpStdioServer;
import dev.justnels.castcli.mcp.McpUsageStore;
import dev.justnels.castcli.mcp.McpUsageSummary;
import dev.justnels.castcli.model.ChatModelFactory;
import dev.justnels.castcli.model.EmbeddingModelFactory;
import dev.justnels.castcli.orchestration.CostSavingsEstimator;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.TokenUsageReport;
import dev.justnels.castcli.orchestration.Workload;
import dev.justnels.castcli.doctor.BuildInfo;
import dev.justnels.castcli.doctor.DoctorReport;
import dev.justnels.castcli.doctor.DoctorService;
import dev.justnels.castcli.doctor.InitService;
import dev.justnels.castcli.observability.CastTelemetry;
import dev.justnels.castcli.observability.CrashReporter;
import dev.justnels.castcli.reliability.ProviderHealthChecker;
import dev.justnels.castcli.tools.ApprovalGate;
import dev.justnels.castcli.tools.AutoApprovalGate;
import dev.justnels.castcli.tools.ConsoleApprovalGate;
import dev.justnels.castcli.tools.DefaultToolSelector;
import dev.justnels.castcli.tools.FastPathExecutor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "cast-cli",
        description = "CastCLI: Route tasks across local and frontier LLMs.",
        mixinStandardHelpOptions = true,
        versionProvider = CastCli.VersionProvider.class,
        subcommands = {CastCli.Ask.class, CastCli.Models.class, CastCli.Commission.class, CastCli.McpServe.class,
                CastCli.Index.class, CastCli.RouteEval.class, CastCli.Memory.class, CastCli.Telemetry.class,
                CastCli.McpUsage.class, CastCli.Doctor.class, CastCli.HealthServer.class, CastCli.Gateway.class,
                CastCli.Completion.class, CastCli.ConfigCmd.class, CastCli.Init.class})
public final class CastCli implements Runnable {
    @Option(names = "--config", description = "Path to CastCLI JSON configuration.",
            defaultValue = "config/harness.local.json")
    private Path configPath;

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override public String[] getVersion() {
            return new String[] {"cast-cli " + BuildInfo.version()};
        }
    }

    public static void main(String[] args) {
        CommandLine cmd = new CommandLine(new CastCli());
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            boolean debug = System.getenv("CASTCLI_DEBUG") != null;
            if (debug) {
                ex.printStackTrace();
            } else {
                System.err.println("Error: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
                System.err.println("Re-run with the CASTCLI_DEBUG=1 environment variable set for a full stack trace.");
            }
            Path crashFile = CrashReporter.write(ex, args);
            if (crashFile != null) {
                System.err.println("Crash report written to " + crashFile + " (local only, never uploaded).");
            }
            return commandLine.getCommandSpec().exitCodeOnExecutionException();
        });
        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    HarnessConfig loadConfig() throws Exception {
        configPath = resolveConfigPath(configPath);
        HarnessConfig config = new ConfigLoader().load(configPath);
        CastTelemetry.initialize(config.observability(),
                Path.of(config.tools().workspaceRoot()).toAbsolutePath().normalize());
        dev.justnels.castcli.lifecycle.ShutdownHookManager.getInstance().register(CastTelemetry.current());
        return config;
    }

    private static final int CONFIG_SEARCH_MAX_DEPTH = 8;

    /** Walks up from the current directory looking for a relative config path, the way git/npm discover
     * their config from a subdirectory, so cast-cli isn't limited to running from the exact directory
     * that holds {@code config/}. Absolute paths and paths that already resolve as given are untouched. */
    static Path resolveConfigPath(Path configPath) {
        return resolveConfigPath(configPath, Path.of("").toAbsolutePath());
    }

    static Path resolveConfigPath(Path configPath, Path startDir) {
        if (configPath.isAbsolute() || Files.isRegularFile(configPath)) {
            return configPath;
        }
        Path dir = startDir;
        for (int i = 0; i < CONFIG_SEARCH_MAX_DEPTH && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(configPath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return configPath;
    }

    @Command(name = "telemetry", description = "Show observability configuration and optionally flush pending exports.",
            mixinStandardHelpOptions = true)
    static final class Telemetry implements Callable<Integer> {
        @CommandLine.ParentCommand private CastCli parent;
        @Option(names = "--flush", description = "Force pending spans and metrics to exporters.") private boolean flush;
        @Override public Integer call() throws Exception {
            HarnessConfig config = parent.loadConfig();
            var observability = config.observability();
            Path tracePath = Path.of(observability.jsonlPath());
            if (!tracePath.isAbsolute()) {
                tracePath = Path.of(config.tools().workspaceRoot()).toAbsolutePath().normalize().resolve(tracePath);
            }
            System.out.printf("Enabled: %s%nService: %s%nLocal trace: %s%nOTLP: %s%s%n",
                    observability.enabled(), observability.serviceName(), tracePath.normalize(),
                    observability.otlpEnabled(), observability.otlpEnabled() ? " (" + observability.otlpEndpoint() + ")" : "");
            if (flush) System.out.println(CastTelemetry.current().forceFlush());
            return 0;
        }
    }

    @Command(name = "doctor", description = "Run system environment, config, memory DB, index, and key health diagnostics.",
            mixinStandardHelpOptions = true)
    static final class Doctor implements Callable<Integer> {
        @CommandLine.ParentCommand private CastCli parent;
        @Option(names = "--json", description = "Output diagnostic report as JSON.") private boolean json;
        @Option(names = "--check-updates",
                description = "Also check GitHub for a newer release (one network call; opt-in, never automatic).")
        private boolean checkUpdates;

        @Override public Integer call() throws Exception {
            HarnessConfig config = parent.loadConfig();
            DoctorService doctor = new DoctorService();
            DoctorReport report = doctor.diagnose(config, parent.configPath, checkUpdates);

            if (json) {
                System.out.println(report.toJson());
                return report.isHealthy() ? 0 : 1;
            }

            System.out.println("CastCLI Diagnostic Report:");
            System.out.println("--------------------------");
            for (DoctorReport.CheckResult res : report.results()) {
                System.out.printf("[%s] %-12s %-25s: %s%n", res.statusSymbol(), res.category(), res.name(), res.message());
            }

            System.out.println("--------------------------");
            if (report.isHealthy()) {
                System.out.println("Result: Everything looks healthy!");
                return 0;
            } else {
                System.out.println("Result: Diagnostics identified errors that require attention.");
                return 1;
            }
        }
    }

    @Command(name = "init", description = "Detect local hardware/Ollama and write a starter config so you don't have to hand-pick a preset.",
            mixinStandardHelpOptions = true)
    static final class Init implements Callable<Integer> {
        @CommandLine.ParentCommand private CastCli parent;

        @Option(names = "--preset", description = "Skip hardware detection and use this preset: 8gb, 12gb, 16gb, 24gb, apple-silicon.")
        private String presetId;

        @Option(names = "--force", description = "Overwrite the target config file if it already exists.")
        private boolean force;

        @Option(names = "--config-dir", description = "Directory containing the harness.vram-*.json presets.",
                defaultValue = "config")
        private Path configDir;

        @Override public Integer call() throws Exception {
            Path target = parent.configPath;
            if (Files.exists(target) && !force) {
                System.out.println("Config already exists at " + target.toAbsolutePath()
                        + " -- pass --force to overwrite, or point --config elsewhere.");
                return 1;
            }

            InitService init = new InitService(configDir);
            InitService.Preset preset;
            String detectionDetail;
            if (presetId != null && !presetId.isBlank()) {
                try {
                    preset = InitService.Preset.fromId(presetId);
                } catch (IllegalArgumentException e) {
                    System.err.println(e.getMessage());
                    return 2;
                }
                detectionDetail = "Preset forced via --preset " + presetId;
            } else {
                InitService.DetectionResult detected = init.detectPreset();
                preset = detected.preset();
                detectionDetail = detected.detectionDetail();
            }

            System.out.println("Hardware: " + detectionDetail);
            System.out.println("Preset:   " + preset.label + " (" + preset.fileName + ")");

            InitService.InitReport report = init.run(preset, detectionDetail, target, init.firstLocalBaseUrl(init.presetPath(preset)));

            System.out.println("Wrote:    " + report.writtenConfigPath().toAbsolutePath());
            System.out.println();

            if (!report.ollamaReachable()) {
                System.out.println("Ollama:   not reachable. Install/start it (https://ollama.com), then re-run "
                        + "'cast-cli doctor' or 'cast-cli models --probe' to confirm readiness.");
            } else if (report.missingModels().isEmpty()) {
                System.out.println("Ollama:   reachable; all required local models are already pulled.");
            } else {
                System.out.println("Ollama:   reachable, but missing " + report.missingModels().size()
                        + " model(s) this preset needs. Pull them with:");
                for (String model : report.missingModels()) {
                    System.out.println("  ollama pull " + model);
                }
            }

            System.out.println();
            System.out.println("Next steps:");
            System.out.println("  cast-cli --config " + target + " doctor");
            System.out.println("  cast-cli --config " + target + " ask \"what time is it\"");
            return 0;
        }
    }

    @Command(name = "config", description = "Inspect and validate configuration settings.",
            mixinStandardHelpOptions = true, subcommands = {ConfigCmd.Validate.class})
    static final class ConfigCmd implements Runnable {
        @CommandLine.ParentCommand private CastCli mainParent;

        @Override public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "validate", description = "Validate configuration for runtime safety and production readiness.",
                mixinStandardHelpOptions = true)
        static final class Validate implements Callable<Integer> {
            @CommandLine.ParentCommand private ConfigCmd configParent;
            @Option(names = "--json", description = "Output validation result as JSON.") private boolean json;

            @Override public Integer call() throws Exception {
                CastCli app = configParent.mainParent;
                HarnessConfig config;
                try {
                    config = app.loadConfig();
                } catch (Exception e) {
                    if (json) {
                        System.out.printf("{\"valid\":false,\"errors\":[\"Failed to parse config: %s\"],\"warnings\":[]}%n",
                                e.getMessage() == null ? "Unknown error" : e.getMessage().replace("\"", "\\\""));
                    } else {
                        System.err.println("Configuration parsing failed: " + e.getMessage());
                    }
                    return 1;
                }

                ConfigValidator validator = new ConfigValidator();
                ConfigValidator.ValidationResult result = validator.validate(config, app.configPath);

                if (json) {
                    ObjectMapper mapper = new ObjectMapper();
                    System.out.println(mapper.writeValueAsString(result));
                } else {
                    System.out.println("CastCLI Configuration Validation Report:");
                    System.out.println("----------------------------------------");
                    System.out.println("Config path: " + app.configPath);
                    System.out.println("Status:      " + (result.valid() ? "VALID [OK]" : "INVALID [FAIL]"));

                    if (!result.errors().isEmpty()) {
                        System.out.println("\nErrors (" + result.errors().size() + "):");
                        for (String err : result.errors()) {
                            System.out.println("  [X] " + err);
                        }
                    }

                    if (!result.warnings().isEmpty()) {
                        System.out.println("\nWarnings (" + result.warnings().size() + "):");
                        for (String warn : result.warnings()) {
                            System.out.println("  [!] " + warn);
                        }
                    }

                    if (result.valid() && result.warnings().isEmpty()) {
                        System.out.println("\nAll configuration checks passed cleanly.");
                    }
                    System.out.println("----------------------------------------");
                }

                return result.valid() ? 0 : 1;
            }
        }
    }

    @Command(name = "mcp-usage", description = "Report MCP utilization, local-model usage, and optional Codex A/B token savings.",
            mixinStandardHelpOptions = true)
    static final class McpUsage implements Callable<Integer> {
        @CommandLine.ParentCommand private CastCli parent;
        @Option(names = "--since-hours", defaultValue = "24",
                description = "Include audit records from the last N hours; use 0 for all records.")
        private double sinceHours;
        @Option(names = "--baseline-codex-tokens",
                description = "Codex tokens measured for the equivalent task without CastCLI MCP.")
        private Long baselineCodexTokens;
        @Option(names = "--delegated-codex-tokens",
                description = "Codex tokens measured for the equivalent task with CastCLI MCP.")
        private Long delegatedCodexTokens;
        @Option(names = "--json", description = "Emit the aggregate report as JSON.")
        private boolean json;
        @Option(names = "--fail-if-unused", description = "Exit 1 when no successful local-model delegation is found.")
        private boolean failIfUnused;

        @Override public Integer call() throws Exception {
            if ((baselineCodexTokens == null) != (delegatedCodexTokens == null)) {
                System.err.println("Provide both --baseline-codex-tokens and --delegated-codex-tokens.");
                return 2;
            }
            if (baselineCodexTokens != null && (baselineCodexTokens < 0 || delegatedCodexTokens < 0)) {
                System.err.println("Codex token measurements must be non-negative.");
                return 2;
            }
            HarnessConfig config = parent.loadConfig();
            Path workspace = Path.of(config.tools().workspaceRoot()).toAbsolutePath().normalize();
            McpUsageStore store = new McpUsageStore(McpUsageStore.resolvePath(config.mcpAudit(), workspace));
            long since = sinceHours <= 0 ? 0
                    : System.currentTimeMillis() - (long) (sinceHours * 3_600_000L);
            McpUsageSummary summary = McpUsageSummary.summarize(store.readSince(since), config);
            Long codexTokenSavings = baselineCodexTokens == null ? null : baselineCodexTokens - delegatedCodexTokens;

            if (json) {
                var output = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                output.put("auditPath", store.path().toString());
                output.set("summary", new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(summary));
                if (codexTokenSavings != null) {
                    output.put("baselineCodexTokens", baselineCodexTokens);
                    output.put("delegatedCodexTokens", delegatedCodexTokens);
                    output.put("measuredCodexTokensSaved", codexTokenSavings);
                    output.put("measuredCodexSavingsPercent",
                            baselineCodexTokens == 0 ? 0 : codexTokenSavings * 100.0 / baselineCodexTokens);
                }
                System.out.println(output.toPrettyString());
            } else {
                printMcpUsage(store, summary, codexTokenSavings);
            }
            return failIfUnused && summary.successfulDelegations() == 0 ? 1 : 0;
        }

        private void printMcpUsage(McpUsageStore store, McpUsageSummary summary, Long codexTokenSavings) {
            HarnessConfig config = parent.loadConfig();
            CostSavingsEstimator estimator = new CostSavingsEstimator(config);
            System.out.println("MCP usage audit: " + store.path());
            System.out.printf("Calls: %d total / %d successful; delegations: %d attempts / %d successful (%.1f%%); ask_local: %d%n",
                    summary.totalCalls(), summary.successfulCalls(), summary.delegationCalls(),
                    summary.successfulDelegations(), summary.successfulDelegationRate() * 100,
                    summary.askLocalCalls());
            System.out.printf("Local usage: %d tokens (%d in / %d out), est. $%.5f; average delegation: %d ms%n",
                    summary.localTotalTokens(), summary.localInputTokens(), summary.localOutputTokens(),
                    summary.localEstimatedCostUsd(), summary.averageDelegationDurationMs());
            if (summary.estimatedFrontierEquivalentCostUsd() > 0) {
                String refLabel = buildFrontierRefLabel(summary, estimator);
                System.out.printf("Estimated frontier equivalent: $%.5f (ref: %s); estimated cost avoided after local cost: $%.5f%n",
                        summary.estimatedFrontierEquivalentCostUsd(), refLabel, summary.estimatedCostAvoidedUsd());
            } else {
                System.out.println("Estimated frontier equivalent: unavailable (configure an enabled FRONTIER_CLOUD reference provider, or pass _meta.callerModel in tool calls).");
            }
            if (!summary.callsByTool().isEmpty()) System.out.println("Calls by tool: " + summary.callsByTool());
            summary.usageByProvider().forEach((provider, usage) ->
                    System.out.printf("  %-20s calls=%d tokens=%d (%d in / %d out) est. $%.5f%n",
                            provider, usage.calls(), usage.totalTokens(), usage.inputTokens(),
                            usage.outputTokens(), usage.estimatedCostUsd()));
            if (codexTokenSavings != null) {
                double percent = baselineCodexTokens == 0 ? 0 : codexTokenSavings * 100.0 / baselineCodexTokens;
                System.out.printf("Measured Codex A/B: %d baseline - %d delegated = %+d tokens (%+.1f%%)%n",
                        baselineCodexTokens, delegatedCodexTokens, codexTokenSavings, percent);
                System.out.printf("All-model processing in delegated run: %d Codex + %d local = %d tokens%n",
                        delegatedCodexTokens, summary.localTotalTokens(),
                        delegatedCodexTokens + summary.localTotalTokens());
            }
        }

        private static String buildFrontierRefLabel(McpUsageSummary summary, CostSavingsEstimator estimator) {
            if (!summary.callerModels().isEmpty()) {
                // Records carried caller model(s) via _meta; show what was seen.
                String joined = String.join(", ", summary.callerModels());
                return joined + " (via _meta)";
            }
            // Fall back to the configured FRONTIER_CLOUD reference provider name.
            return estimator.referenceProvider()
                    .map(p -> p.modelName() + " [" + p.id() + "]")
                    .orElse("unknown");
        }
    }

    static MemoryStore openMemory(HarnessConfig config) {
        Path workspace = Path.of(config.tools().workspaceRoot()).toAbsolutePath().normalize();
        Path configured = Path.of(config.memory().databasePath());
        return new SqliteMemoryStore(configured.isAbsolute() ? configured : workspace.resolve(configured));
    }

    @Command(name = "route-eval", description = "Evaluate routing and tool-selection policy without model calls.", mixinStandardHelpOptions = true)
    static final class RouteEval implements Callable<Integer> {
        @CommandLine.ParentCommand private CastCli parent;
        @Parameters(index = "0", description = "Evaluation dataset JSON") private Path dataset;

        @Override public Integer call() throws Exception {
            RoutingEvaluator evaluator = new RoutingEvaluator();
            RoutingEvaluationReport report = evaluator.evaluate(parent.loadConfig(), evaluator.load(dataset));
            System.out.printf("Dataset: %s - %d/%d cases passed%n", report.dataset(), report.passed(), report.total());
            System.out.printf("Provider accuracy: %.1f%%; tier accuracy: %.1f%%; cloud share: %.1f%%; privacy violations: %d%n",
                    report.providerAccuracy() * 100, report.tierAccuracy() * 100,
                    report.cloudRouteShare() * 100, report.privacyViolations());
            System.out.printf("Average selected provider rate: $%.2f per million input+output tokens%n",
                    report.averageCombinedRatePerMillionTokens());
            for (RoutingEvaluationReport.Decision decision : report.decisions()) {
                System.out.printf("%-24s -> %-18s %-15s score=%6.2f %s%n", decision.caseId(),
                        decision.providerId(), decision.tier(), decision.score(),
                        decision.failures().isEmpty() ? "PASS" : "FAIL " + decision.failures());
            }
            return report.passed() == report.total() ? 0 : 1;
        }
    }

    @Command(name = "memory", description = "Inspect and curate durable shared memory.", mixinStandardHelpOptions = true,
            subcommands = {Memory.Remember.class, Memory.Recall.class, Memory.ListEntries.class, Memory.Forget.class, Memory.Vacuum.class})
    static final class Memory implements Runnable {
        @CommandLine.ParentCommand private CastCli parent;
        @Override public void run() { CommandLine.usage(this, System.out); }

        @Command(name = "remember", description = "Store a namespaced memory.")
        static final class Remember implements Callable<Integer> {
            @CommandLine.ParentCommand private Memory memory;
            @Option(names = "--namespace") private String namespace;
            @Option(names = "--author", defaultValue = "Developer") private String author;
            @Option(names = "--tags", split = ",") private java.util.List<String> tags;
            @Option(names = "--read-only") private boolean readOnly;
            @Parameters(index = "0", description = "Topic") private String topic;
            @Parameters(index = "1", description = "Memory content") private String content;
            @Override public Integer call() throws Exception {
                HarnessConfig config = memory.parent.loadConfig();
                String ns = namespace == null ? config.memory().defaultNamespace() : namespace;
                try (MemoryStore store = openMemory(config)) {
                    MemoryEntry entry = store.put(new MemoryDraft(ns, "shared", topic, content, author, "cli", tags,
                            0.5, 0.9, null, readOnly, null));
                    System.out.printf("Stored %s v%d in %s%n", entry.id(), entry.version(), entry.namespace());
                }
                return 0;
            }
        }

        @Command(name = "recall", description = "Hybrid-search shared memory.")
        static final class Recall implements Callable<Integer> {
            @CommandLine.ParentCommand private Memory memory;
            @Option(names = "--namespace") private String namespace;
            @Option(names = "--limit", defaultValue = "10") private int limit;
            @Parameters(index = "0", description = "Query") private String query;
            @Override public Integer call() throws Exception {
                HarnessConfig config = memory.parent.loadConfig();
                String ns = namespace == null ? config.memory().defaultNamespace() : namespace;
                try (MemoryStore store = openMemory(config)) {
                    for (MemoryEntry entry : store.search(MemoryQuery.inNamespace(query, ns, limit))) printMemory(entry);
                }
                return 0;
            }
        }

        @Command(name = "list", description = "List recent memories.")
        static final class ListEntries implements Callable<Integer> {
            @CommandLine.ParentCommand private Memory memory;
            @Option(names = "--namespace") private String namespace;
            @Option(names = "--limit", defaultValue = "20") private int limit;
            @Override public Integer call() throws Exception {
                HarnessConfig config = memory.parent.loadConfig();
                String ns = namespace == null ? config.memory().defaultNamespace() : namespace;
                try (MemoryStore store = openMemory(config)) { for (MemoryEntry entry : store.list(ns, limit)) printMemory(entry); }
                return 0;
            }
        }

        @Command(name = "forget", description = "Delete a writable memory with optimistic version checking.")
        static final class Forget implements Callable<Integer> {
            @CommandLine.ParentCommand private Memory memory;
            @Parameters(index = "0") private String id;
            @Option(names = "--version", required = true) private int version;
            @Override public Integer call() throws Exception {
                try (MemoryStore store = openMemory(memory.parent.loadConfig())) {
                    boolean deleted = store.delete(id, version);
                    System.out.println(deleted ? "Deleted." : "Not deleted; check ID, version, or read-only status.");
                    return deleted ? 0 : 1;
                }
            }
        }

        @Command(name = "vacuum", description = "Purge expired memories, apply retention policies, optimize SQLite database, and optionally write a backup snapshot.")
        static final class Vacuum implements Callable<Integer> {
            @CommandLine.ParentCommand private Memory memory;
            @Option(names = "--retention-days", defaultValue = "0", description = "Retention period in days for purging non-read-only memories. 0 disables retention purging.")
            private int retentionDays;
            @Option(names = "--backup-path", description = "Optional destination file path for creating an online SQLite backup snapshot.")
            private Path backupPath;

            @Override public Integer call() throws Exception {
                HarnessConfig config = memory.parent.loadConfig();
                try (MemoryStore store = openMemory(config)) {
                    int expiredPurged = store.purgeExpired();
                    int retentionPurged = store.purgeOlderThan(retentionDays);
                    if (store instanceof SqliteMemoryStore sqliteStore) {
                        sqliteStore.optimize();
                        if (backupPath != null) {
                            sqliteStore.backup(backupPath);
                            System.out.println("Database backup snapshot written to " + backupPath.toAbsolutePath().normalize());
                        }
                    }
                    System.out.printf("Vacuum complete: %d expired memories purged, %d old memories purged under %d-day retention.%n",
                            expiredPurged, retentionPurged, retentionDays);
                }
                return 0;
            }
        }

        private static void printMemory(MemoryEntry entry) {
            System.out.printf("%s v%d [%s/%s] %s - %s%n%s%n%n", entry.id(), entry.version(), entry.namespace(),
                    entry.scope(), entry.topic(), entry.author(), entry.content());
        }
    }

    @Command(name = "ask", description = "Route a prompt and run it.", mixinStandardHelpOptions = true)
    static final class Ask implements Callable<Integer> {
        @CommandLine.ParentCommand
        private CastCli parent;

        @Parameters(arity = "1..*", description = "Prompt text")
        private String[] prompt;

        @Option(names = {"-w", "--workload"}, defaultValue = "AUTO",
                description = "AUTO, QUICK, CODE, or REASONING")
        private Workload workload;

        @Option(names = "--tier", description = "Force SMALL_LOCAL, LARGE_LOCAL, or FRONTIER_CLOUD")
        private ModelTier tier;

        @Option(names = "--stream", description = "Stream the response token-by-token (no tools).")
        private boolean stream;

        @Option(names = {"-y", "--yes"}, description = "Auto-approve write/exec tool calls instead of prompting.")
        private boolean autoApprove;

        @Override
        public Integer call() throws Exception {
            HarnessConfig config = parent.loadConfig();
            TaskRequest task = new TaskRequest(String.join(" ", prompt), workload, tier);

            CostSavingsEstimator savingsEstimator = new CostSavingsEstimator(config);

            if (stream) {
                HarnessOrchestrator orchestrator = new HarnessOrchestrator(config);
                HarnessOrchestrator.Outcome outcome = orchestrator.runStreaming(task, token -> {
                    System.out.print(token);
                    System.out.flush();
                });
                System.out.println();
                System.out.println("Trace: " + outcome.traceId());
                printTokenSummary(outcome, savingsEstimator);
                return 0;
            }

            ApprovalGate gate = autoApprove ? AutoApprovalGate.INSTANCE : new ConsoleApprovalGate();
            try (McpClientManager mcp = McpClientManager.start(config.mcpServers())) {
                HarnessOrchestrator orchestrator = new HarnessOrchestrator(
                        config, new ChatModelFactory(), new DefaultToolSelector(), new FastPathExecutor(), gate, mcp.toolProvider());
                HarnessOrchestrator.Outcome outcome = orchestrator.run(task);
                System.out.printf("[%s / %s] (duration: %d ms, fastPath: %b)%n",
                        outcome.provider().id(), outcome.provider().modelName(),
                        outcome.durationMs(), outcome.fastPath());
                if (outcome.toolsSelected() != null && !outcome.toolsSelected().isEmpty()) {
                    System.out.println("Selected Tools: " + String.join(", ", outcome.toolsSelected()));
                }
                if (outcome.toolsUsed() != null && !outcome.toolsUsed().isEmpty()) {
                    System.out.println("Executed Tools: " + String.join(", ", outcome.toolsUsed()));
                }
                System.out.println(outcome.answer());
                System.out.println("Trace: " + outcome.traceId());
                printTokenSummary(outcome, savingsEstimator);
            }
            return 0;
        }

        private static void printTokenSummary(HarnessOrchestrator.Outcome outcome, CostSavingsEstimator savingsEstimator) {
            if (outcome.inputTokens() > 0 || outcome.outputTokens() > 0) {
                System.out.printf("Tokens: %d in / %d out (est. $%.5f)%n",
                        outcome.inputTokens(), outcome.outputTokens(), outcome.estimatedCostUsd());
            }
            if (savingsEstimator.isOffloaded(outcome.provider()) && savingsEstimator.hasReference()
                    && (outcome.inputTokens() > 0 || outcome.outputTokens() > 0)) {
                double avoided = savingsEstimator.estimateAvoidedCostUsd(
                        outcome.provider(), outcome.inputTokens(), outcome.outputTokens());
                System.out.printf("Frontier savings: %d tokens offloaded to %s instead of %s (est. $%.5f avoided)%n",
                        outcome.inputTokens() + outcome.outputTokens(), outcome.provider().modelName(),
                        savingsEstimator.referenceProvider().orElseThrow().modelName(), avoided);
            }
        }
    }

    @Command(name = "commission", description = "Run hierarchical multi-agent PM and labor execution.", mixinStandardHelpOptions = true)
    static final class Commission implements Callable<Integer> {
        @CommandLine.ParentCommand
        private CastCli parent;

        @Parameters(arity = "0..*", description = "Project goal text (omit when using --resume)")
        private String[] goal;

        @Option(names = {"-y", "--yes"}, description = "Auto-approve write/exec tool calls instead of prompting.")
        private boolean autoApprove;

        @Option(names = "--resume", description = "Resume from a checkpoint file written by a prior commission run.")
        private Path resumeFrom;

        @Override
        public Integer call() throws Exception {
            HarnessConfig config = parent.loadConfig();
            String fullGoal = (goal == null || goal.length == 0) ? null : String.join(" ", goal);
            if (fullGoal == null && resumeFrom == null) {
                System.err.println("Provide a goal, or --resume <checkpoint> with the original goal text.");
                return 2;
            }

            ApprovalGate gate = autoApprove ? AutoApprovalGate.INSTANCE : new ConsoleApprovalGate();
            try (McpClientManager mcp = McpClientManager.start(config.mcpServers())) {
                HarnessOrchestrator orchestrator = new HarnessOrchestrator(
                        config, new ChatModelFactory(), new DefaultToolSelector(), new FastPathExecutor(), gate, mcp.toolProvider());
                AgentTeam team = new AgentTeam(config, orchestrator);
                CommissioningResult result = team.commission(fullGoal, resumeFrom);

                System.out.printf("=== AGENT TEAM COMMISSIONING REPORT (Duration: %d ms) ===%n", result.totalDurationMs());
                System.out.println("Goal: " + result.plan().goal());
                System.out.println("\n--- Project Plan Subtasks ---");
                for (SubTask task : result.completedTasks()) {
                    System.out.printf("[%d] %s (%s) - Status: %s%n", task.id(), task.title(), task.assignedRole(), task.status());
                }
                System.out.println("\n--- PM Commissioning Report ---");
                System.out.println(result.commissioningSummary());
                System.out.printf("%nTokens: %d in / %d out (est. $%.5f)%n",
                        result.totalInputTokens(), result.totalOutputTokens(), result.estimatedCostUsd());
                if (result.referenceFrontierModel() != null && result.tokensOffloadedToLocal() > 0) {
                    System.out.printf(
                            "Frontier savings: %d tokens offloaded to local/small tiers instead of %s (est. $%.5f avoided)%n",
                            result.tokensOffloadedToLocal(), result.referenceFrontierModel(),
                            result.estimatedFrontierCostAvoidedUsd());
                }
                printTokenUsageByProvider(result.tokenUsageByProvider());
                System.out.println("Checkpoint: " + result.checkpointPath());
            }
            return 0;
        }

        private static void printTokenUsageByProvider(TokenUsageReport.Summary summary) {
            if (summary.byProvider().isEmpty()) {
                return;
            }
            System.out.println("\n--- Tokens by Provider ---");
            for (TokenUsageReport.ProviderUsage usage : summary.byProvider()) {
                System.out.printf("%-24s %-14s calls=%-4d tokens=%-8d (%d in / %d out) est. $%.5f%n",
                        usage.providerId(), usage.tier(), usage.calls(), usage.totalTokens(),
                        usage.inputTokens(), usage.outputTokens(), usage.estimatedCostUsd());
            }
            System.out.printf(
                    "Cloud vs local: %d cloud tokens / %d local tokens (%.1f%% cloud, %+d local-minus-cloud)%n",
                    summary.cloudTokens(), summary.localTokens(), summary.cloudShare() * 100,
                    summary.localMinusCloudTokens());
        }
    }

    @Command(name = "mcp-serve", description = "Run as an MCP server over stdio, exposing cheap local/small tiers to a calling agent.",
            mixinStandardHelpOptions = true)
    static final class McpServe implements Callable<Integer> {
        @CommandLine.ParentCommand
        private CastCli parent;

        @Override
        public Integer call() throws Exception {
            System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
            HarnessConfig config = parent.loadConfig();
            McpStdioServer.forStdio(config).serve();
            return 0;
        }
    }

    @Command(name = "index", description = "Build/refresh the semantic (embedding) workspace search index, or query it directly.",
            mixinStandardHelpOptions = true)
    static final class Index implements Callable<Integer> {
        @CommandLine.ParentCommand
        private CastCli parent;

        @Option(names = "--query", description = "Skip rebuilding; semantically search the existing index for this text instead.")
        private String query;

        @Option(names = "--max-results", defaultValue = "10", description = "Max results for --query, from 1 to 50.")
        private int maxResults;

        @Override
        public Integer call() throws Exception {
            HarnessConfig config = parent.loadConfig();
            if (!config.embeddings().enabled()) {
                System.err.println("embeddings.enabled is false in this config; nothing to index. "
                        + "See docs/ARCHITECTURE.md for an example embeddings block.");
                return 2;
            }
            WorkspaceEmbeddingIndex index = new WorkspaceEmbeddingIndex(
                    config.embeddings(), Path.of(config.tools().workspaceRoot()),
                    new EmbeddingModelFactory().create(config.embeddings()));

            if (query != null && !query.isBlank()) {
                for (WorkspaceEmbeddingIndex.SearchHit hit : index.search(query, maxResults)) {
                    System.out.printf("%s:%d-%d (score %.3f)%n", hit.sourcePath(), hit.startLine(), hit.endLine(), hit.score());
                    System.out.println(hit.text());
                    System.out.println();
                }
                return 0;
            }

            WorkspaceEmbeddingIndex.IndexReport report = index.rebuild();
            System.out.printf(
                    "Indexed %d files (%d embedded, %d unchanged, %d removed) into %d chunks in %d ms%n",
                    report.filesScanned(), report.filesEmbedded(), report.filesUnchanged(),
                    report.filesRemoved(), report.totalChunks(), report.durationMs());
            System.out.printf("Embedding tokens used: %d (est. $%.5f)%n",
                    report.inputTokensUsed(), report.estimatedCostUsd());
            System.out.println("Index file: " + index.indexFile());
            return 0;
        }
    }

    @Command(name = "models", description = "Show configured providers and availability.", mixinStandardHelpOptions = true)
    static final class Models implements Callable<Integer> {
        @CommandLine.ParentCommand
        private CastCli parent;

        @Option(names = "--probe", description = "Call each enabled provider's /models endpoint and report live readiness.")
        private boolean probe;

        @Override
        public Integer call() throws Exception {
            ProviderHealthChecker checker = probe ? new ProviderHealthChecker() : null;
            for (ProviderConfig provider : parent.loadConfig().providers()) {
                System.out.printf("%-18s %-16s enabled=%-5s credentials=%-7s model=%-30s cost=$%.2f/$%.2f per M tok (in/out)%n",
                        provider.id(), provider.tier(), provider.enabled(),
                        provider.credentialsAvailable() ? "ready" : "missing", provider.modelName(),
                        provider.costPerMillionInputTokens(), provider.costPerMillionOutputTokens());
                if (checker != null) {
                    ProviderHealthChecker.Result result = checker.check(provider);
                    System.out.printf("  probe: ready=%-5s status=%-3d latency=%d ms (%s)%n",
                            result.ready(), result.statusCode(), result.latencyMs(), result.detail());
                }
            }
            return 0;
        }
    }

    @Command(name = "health-server", description = "Run HTTP health check and Prometheus metrics server daemon.",
            mixinStandardHelpOptions = true)
    static final class HealthServer implements Callable<Integer> {
        @CommandLine.ParentCommand private CastCli parent;

        @Option(names = "--port", defaultValue = "8080", description = "Port to listen on for HTTP health/metrics endpoints.")
        private int port;

        @Option(names = "--bind", defaultValue = "127.0.0.1",
                description = "Address to bind (default: loopback; use 0.0.0.0 only on a trusted network).")
        private String bindAddress;

        @Override public Integer call() throws Exception {
            HarnessConfig config = parent.loadConfig();
            if (!"127.0.0.1".equals(bindAddress) && !"localhost".equalsIgnoreCase(bindAddress)
                    && !"::1".equals(bindAddress)) {
                System.err.println("WARNING: detailed health diagnostics will be reachable on " + bindAddress
                        + "; place this endpoint behind network access controls.");
            }
            try (dev.justnels.castcli.doctor.HealthHttpServer server =
                         new dev.justnels.castcli.doctor.HealthHttpServer(
                                 bindAddress, port, config, parent.configPath)) {
                server.start();
                dev.justnels.castcli.lifecycle.ShutdownHookManager.getInstance().register(server);
                System.out.println("Health HTTP server started on " + server.getAddress()
                        + ". Press Ctrl+C to stop.");
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }
    }

    @Command(name = "gateway",
            description = "Run the OpenAI-compatible inbound gateway (phase 1: non-streaming /v1/chat/completions "
                    + "and /v1/models, no client-tool passthrough) so existing OpenAI-SDK clients can route through "
                    + "CastCLI by changing only their base URL.",
            mixinStandardHelpOptions = true)
    static final class Gateway implements Callable<Integer> {
        @CommandLine.ParentCommand private CastCli parent;

        @Option(names = "--port", defaultValue = "8081", description = "Port to listen on for the gateway.")
        private int port;

        @Option(names = "--bind", defaultValue = "127.0.0.1",
                description = "Address to bind (default: loopback; a non-loopback bind requires --token).")
        private String bindAddress;

        @Option(names = "--token",
                description = "Bearer token clients must present as 'Authorization: Bearer <token>'. "
                        + "Required when --bind is not loopback; optional (but recommended) on loopback.")
        private String token;

        @Override public Integer call() throws Exception {
            HarnessConfig config = parent.loadConfig();
            try (dev.justnels.castcli.gateway.GatewayHttpServer server =
                         new dev.justnels.castcli.gateway.GatewayHttpServer(bindAddress, port, config, token)) {
                server.start();
                dev.justnels.castcli.lifecycle.ShutdownHookManager.getInstance().register(server);
                System.out.println("CastCLI OpenAI-compatible gateway started on " + server.getAddress()
                        + ". Press Ctrl+C to stop.");
                if (token == null || token.isBlank()) {
                    System.out.println("No --token configured; requests are unauthenticated (loopback only).");
                }
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }
    }

    @Command(name = "completion", description = "Generate shell auto-completion scripts for cast-cli.",
            mixinStandardHelpOptions = true)
    static final class Completion implements Callable<Integer> {
        @Option(names = "--shell", description = "Target shell: bash, zsh, powershell.", defaultValue = "bash")
        private String shell;

        @Override
        public Integer call() {
            CommandLine cmd = new CommandLine(new CastCli());
            String script;
            switch (shell.toLowerCase(java.util.Locale.ROOT)) {
                case "bash" -> script = picocli.AutoComplete.bash("cast-cli", cmd);
                case "zsh" -> script = zshCompletion();
                case "powershell", "pwsh" -> script = powershellCompletion();
                default -> {
                    System.err.println("Unsupported shell '" + shell + "'. Use bash, zsh, or powershell.");
                    return 2;
                }
            }
            System.out.println(script);
            return 0;
        }

        private static String zshCompletion() {
            return """
                    #compdef cast-cli
                    _cast_cli() {
                      local -a commands
                      commands=(
                        'init:detect hardware and write starter configuration'
                        'doctor:run diagnostics'
                        'config:validate configuration'
                        'ask:route and run a prompt'
                        'commission:run the multi-agent pipeline'
                        'models:list configured providers'
                        'memory:inspect or update project memory'
                        'index:build or query the semantic index'
                        'route-eval:evaluate routing policy'
                        'telemetry:inspect or flush telemetry'
                        'mcp-serve:serve local delegation tools over stdio'
                        'mcp-usage:report MCP delegation utilization'
                        'health-server:serve health and metrics endpoints'
                        'gateway:serve the OpenAI-compatible inbound gateway'
                        'completion:generate shell completion'
                      )
                      _arguments -C \
                        '(-h --help)'{-h,--help}'[show help]' \
                        '(-V --version)'{-V,--version}'[show version]' \
                        '--config[configuration file]:file:_files' \
                        '1:command:->command' \
                        '*::argument:->args'
                      [[ $state == command ]] && _describe 'command' commands
                    }
                    compdef _cast_cli cast-cli
                    """;
        }

        private static String powershellCompletion() {
            return """
                    Register-ArgumentCompleter -Native -CommandName cast-cli -ScriptBlock {
                        param($wordToComplete, $commandAst, $cursorPosition)
                        $candidates = @(
                            '--help','--version','--config',
                            'init','doctor','config','ask','commission','models','memory','index',
                            'route-eval','telemetry','mcp-serve','mcp-usage','health-server','gateway','completion'
                        )
                        $candidates |
                            Where-Object { $_ -like "$wordToComplete*" } |
                            ForEach-Object {
                                [System.Management.Automation.CompletionResult]::new(
                                    $_, $_, 'ParameterValue', $_)
                            }
                    }
                    """;
        }
    }
}
