package dev.justnels.castcli.doctor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.doctor.DoctorReport.CheckResult;
import dev.justnels.castcli.doctor.DoctorReport.Status;
import dev.justnels.castcli.memory.SqliteMemoryMigrator;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes comprehensive health and diagnostic checks on CastCLI environment, workspace,
 * configuration, SQLite database, embedding index, and provider keys.
 */
public final class DoctorService {
    private final UpdateChecker updateChecker;

    public DoctorService() {
        this(new UpdateChecker());
    }

    DoctorService(UpdateChecker updateChecker) {
        this.updateChecker = updateChecker;
    }

    public DoctorReport diagnose(HarnessConfig config, Path configPath) {
        return diagnose(config, configPath, false);
    }

    /**
     * @param checkUpdates when true, makes one best-effort network call to GitHub to compare the
     *                      running version against the latest release. Opt-in only -- CastCLI does
     *                      not phone home unless explicitly asked to.
     */
    public DoctorReport diagnose(HarnessConfig config, Path configPath, boolean checkUpdates) {
        List<CheckResult> results = new ArrayList<>();

        if (checkUpdates) {
            UpdateChecker.Result update = updateChecker.check(BuildInfo.version());
            if (!update.checked()) {
                results.add(new CheckResult("Version", "Update check", Status.WARNING,
                        "Could not check for updates: " + update.detail()));
            } else if (update.updateAvailable()) {
                results.add(new CheckResult("Version", "Update check", Status.WARNING,
                        "Running " + update.currentVersion() + "; " + update.latestVersion()
                                + " is available: https://github.com/JustinANelson/JavaLocalLLMHarness/releases/latest"));
            } else {
                results.add(new CheckResult("Version", "Update check", Status.OK,
                        "Running " + update.currentVersion() + " (latest)"));
            }
        }

        // 1. Workspace Root Check
        Path workspace = Path.of(config.tools().workspaceRoot()).toAbsolutePath().normalize();
        if (Files.exists(workspace) && Files.isDirectory(workspace)) {
            if (Files.isWritable(workspace)) {
                results.add(new CheckResult("Workspace", "Root directory", Status.OK, workspace.toString()));
            } else {
                results.add(new CheckResult("Workspace", "Root directory", Status.WARNING, "Directory is read-only: " + workspace));
            }
        } else {
            results.add(new CheckResult("Workspace", "Root directory", Status.ERROR, "Directory does not exist: " + workspace));
        }

        // 2. Configuration Check
        if (Files.exists(configPath)) {
            results.add(new CheckResult("Config", "Configuration file", Status.OK, configPath.toString()));
        } else {
            results.add(new CheckResult("Config", "Configuration file", Status.WARNING, "Custom config file not found at " + configPath));
        }

        if (config.providers() != null && !config.providers().isEmpty()) {
            long enabled = config.providers().stream().filter(ProviderConfig::enabled).count();
            results.add(new CheckResult("Config", "Providers loaded", Status.OK, enabled + " enabled out of " + config.providers().size() + " total"));
        } else {
            results.add(new CheckResult("Config", "Providers loaded", Status.ERROR, "No model providers configured"));
        }

        // 3. Provider Environment Keys, Endpoint Reachability & Local Model Availability
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        Map<String, List<String>> installedModelsByBaseUrl = new HashMap<>();

        for (ProviderConfig provider : config.providers()) {
            if (!provider.enabled()) continue;
            String envVar = provider.apiKeyEnv();
            if (envVar != null && !envVar.isBlank()) {
                String val = System.getenv(envVar);
                if (val != null && !val.isBlank()) {
                    results.add(new CheckResult("Providers", provider.id() + " API Key", Status.OK, envVar + " is set"));
                } else {
                    results.add(new CheckResult("Providers", provider.id() + " API Key", Status.WARNING, envVar + " is missing from environment"));
                }
            }

            String baseUrl = provider.baseUrl();
            if (baseUrl != null && !baseUrl.isBlank() && (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build();
                    HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
                    results.add(new CheckResult("Providers", provider.id() + " Endpoint", Status.OK,
                            "Reachable at " + baseUrl + " (HTTP " + resp.statusCode() + ")"));
                } catch (Exception e) {
                    results.add(new CheckResult("Providers", provider.id() + " Endpoint", Status.WARNING,
                            "Unreachable at " + baseUrl + " (" + e.getMessage() + ")"));
                }
            }

            if (isLocalOllamaUrl(baseUrl)) {
                List<String> installed = installedModelsByBaseUrl.computeIfAbsent(baseUrl, this::probeOllamaModels);
                if (installed.isEmpty()) {
                    results.add(new CheckResult("Providers", provider.id() + " Model", Status.WARNING,
                            "Could not list installed models at " + baseUrl + " -- unable to confirm '"
                                    + provider.modelName() + "' is pulled"));
                } else if (!installed.contains(provider.modelName())) {
                    results.add(new CheckResult("Providers", provider.id() + " Model", Status.ERROR,
                            "Model '" + provider.modelName() + "' is not pulled. Run: ollama pull " + provider.modelName()));
                } else {
                    results.add(new CheckResult("Providers", provider.id() + " Model", Status.OK,
                            "'" + provider.modelName() + "' is pulled"));
                }
            }
        }

        // 3b. Cost Guardrail Check
        boolean hasPaidEnabledProvider = config.providers().stream().anyMatch(p ->
                p.enabled() && (p.costPerMillionInputTokens() > 0 || p.costPerMillionOutputTokens() > 0));
        boolean hasCostCap = config.reliability().maxCostUsdPerTask() > 0 || config.reliability().maxCumulativeCostUsd() > 0;
        if (hasPaidEnabledProvider && !hasCostCap) {
            results.add(new CheckResult("Reliability", "Cost guardrail", Status.WARNING,
                    "A paid provider is enabled but reliability.maxCostUsdPerTask/maxCumulativeCostUsd are unset (0 = unlimited spend)"));
        } else if (hasPaidEnabledProvider) {
            results.add(new CheckResult("Reliability", "Cost guardrail", Status.OK,
                    "Cost cap configured (per-task: $" + config.reliability().maxCostUsdPerTask()
                            + ", cumulative: $" + config.reliability().maxCumulativeCostUsd() + ")"));
        }

        // 4. Shared Memory Database Check
        Path configuredMemoryDb = Path.of(config.memory().databasePath());
        Path memoryDb = configuredMemoryDb.isAbsolute() ? configuredMemoryDb : workspace.resolve(configuredMemoryDb);
        if (Files.exists(memoryDb)) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + memoryDb.toAbsolutePath())) {
                try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
                    if (rs.next() && "ok".equalsIgnoreCase(rs.getString(1))) {
                        int dbVersion = SqliteMemoryMigrator.getCurrentDbVersion(conn);
                        results.add(new CheckResult("Memory", "SQLite Database", Status.OK, "Integrity OK (Schema v" + dbVersion + ")"));
                    } else {
                        results.add(new CheckResult("Memory", "SQLite Database", Status.ERROR, "Database integrity check failed"));
                    }
                }
            } catch (Exception e) {
                results.add(new CheckResult("Memory", "SQLite Database", Status.ERROR, "Failed to connect: " + e.getMessage()));
            }
        } else {
            results.add(new CheckResult("Memory", "SQLite Database", Status.OK, "Not initialized yet (" + memoryDb + ")"));
        }

        // 5. Semantic Index Binary Check
        Path indexFile = workspace.resolve(config.embeddings().indexPath());
        if (Files.exists(indexFile)) {
            try {
                long bytes = Files.size(indexFile);
                results.add(new CheckResult("Embeddings", "Semantic Index", Status.OK, indexFile.getFileName() + " (" + (bytes / 1024) + " KB)"));
            } catch (Exception e) {
                results.add(new CheckResult("Embeddings", "Semantic Index", Status.WARNING, "Unable to read index file size"));
            }
        } else {
            results.add(new CheckResult("Embeddings", "Semantic Index", Status.WARNING, "Index file not built yet. Run 'cast-cli index' to generate."));
        }

        // 6. System Resources & Storage Space Check
        try {
            File workspaceFile = workspace.toFile();
            long freeBytes = workspaceFile.getUsableSpace();
            long freeMb = freeBytes / (1024 * 1024);
            if (freeMb < 500) {
                results.add(new CheckResult("System", "Disk Space", Status.WARNING, "Low disk space on workspace drive: " + freeMb + " MB available"));
            } else {
                results.add(new CheckResult("System", "Disk Space", Status.OK, freeMb + " MB available on workspace drive"));
            }
        } catch (Exception e) {
            results.add(new CheckResult("System", "Disk Space", Status.WARNING, "Could not query available disk space: " + e.getMessage()));
        }

        Runtime runtime = Runtime.getRuntime();
        long maxMemMb = runtime.maxMemory() / (1024 * 1024);
        long totalMemMb = runtime.totalMemory() / (1024 * 1024);
        long freeMemMb = runtime.freeMemory() / (1024 * 1024);
        results.add(new CheckResult("System", "JVM Memory", Status.OK,
                "Max: " + maxMemMb + " MB, Allocated: " + totalMemMb + " MB, Free: " + freeMemMb + " MB"));

        // 6b. Stale Legacy Index Directory Check
        Path legacyHarnessDir = workspace.resolve(".harness");
        if (Files.exists(legacyHarnessDir)) {
            results.add(new CheckResult("Embeddings", "Legacy index directory", Status.WARNING,
                    ".harness/ is a leftover from before the .cast rename and is no longer read -- safe to delete: " + legacyHarnessDir));
        }

        // 7. Database Backup Directory Check
        Path backupDir = workspace.resolve(".cast/backups");
        try {
            if (!Files.exists(backupDir)) {
                Files.createDirectories(backupDir);
            }
            if (Files.isWritable(backupDir)) {
                results.add(new CheckResult("Memory", "Backup Storage", Status.OK, backupDir.toString() + " is ready"));
            } else {
                results.add(new CheckResult("Memory", "Backup Storage", Status.WARNING, "Backup directory is read-only: " + backupDir));
            }
        } catch (Exception e) {
            results.add(new CheckResult("Memory", "Backup Storage", Status.WARNING, "Failed to verify backup directory: " + e.getMessage()));
        }

        return new DoctorReport(results);
    }

    private static boolean isLocalOllamaUrl(String baseUrl) {
        return baseUrl != null && (baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1"));
    }

    /** Best-effort probe of a local Ollama instance's installed model tags. Returns empty list if unreachable. */
    private List<String> probeOllamaModels(String ollamaBaseUrl) {
        String tagsUrl = ollamaBaseUrl.replaceAll("/v1/?$", "") + "/api/tags";
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(tagsUrl))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return List.of();
            }
            JsonNode root = new ObjectMapper().readTree(response.body());
            List<String> names = new ArrayList<>();
            for (JsonNode model : root.path("models")) {
                String name = model.path("name").asText(null);
                if (name != null) names.add(name);
            }
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }
}
