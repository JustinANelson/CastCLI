package dev.justnels.castcli.doctor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks a hardware-appropriate config preset, writes it as the local config, and reports which
 * local models still need to be pulled via Ollama. Best-effort: falls back to a conservative
 * preset whenever hardware can't be detected rather than failing the command.
 *
 * <p>Preset JSON is read from the {@code /presets/} classpath resources bundled into the jar by default,
 * so {@code init} works from any working directory -- including a standalone release/jpackage bundle where
 * no {@code config/} directory exists on disk. Pass an explicit {@code configDir} (e.g. via
 * {@code --config-dir}) to read presets from the filesystem instead.
 */
public final class InitService {

    public enum Preset {
        VRAM_8GB("harness.vram-8gb.json", "8GB VRAM"),
        VRAM_12GB("harness.vram-12gb.json", "12GB VRAM"),
        VRAM_16GB("harness.vram-16gb.json", "16GB VRAM"),
        VRAM_24GB("harness.vram-24gb.json", "24GB VRAM"),
        APPLE_SILICON("harness.apple-silicon.json", "Apple Silicon");

        public final String fileName;
        public final String label;

        Preset(String fileName, String label) {
            this.fileName = fileName;
            this.label = label;
        }

        public static Preset fromId(String id) {
            return switch (id.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "8gb" -> VRAM_8GB;
                case "12gb" -> VRAM_12GB;
                case "16gb" -> VRAM_16GB;
                case "24gb" -> VRAM_24GB;
                case "apple-silicon", "apple", "mac" -> APPLE_SILICON;
                default -> throw new IllegalArgumentException(
                        "Unknown preset '" + id + "'. Expected one of: 8gb, 12gb, 16gb, 24gb, apple-silicon");
            };
        }
    }

    public record DetectionResult(Preset preset, String detectionDetail) {
    }

    public record InitReport(Preset preset, String detectionDetail, Path writtenConfigPath,
                              boolean ollamaReachable, List<String> requiredLocalModels,
                              List<String> installedModels, List<String> missingModels) {
    }

    private final Path configDir;

    /** Reads presets from the {@code /presets/} classpath resources bundled into the jar. */
    public InitService() {
        this.configDir = null;
    }

    /** Reads presets from {@code configDir} on the filesystem instead of the classpath. */
    public InitService(Path configDir) {
        this.configDir = configDir;
    }

    /** Detects a hardware preset. Never throws; falls back to the smallest preset on any failure. */
    public DetectionResult detectPreset() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);

        if (os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm"))) {
            return new DetectionResult(Preset.APPLE_SILICON, "Detected Apple Silicon (" + arch + ")");
        }

        Long vramMb = detectNvidiaVramMb();
        if (vramMb != null) {
            Preset preset = mapVramToPreset(vramMb);
            return new DetectionResult(preset, "Detected " + vramMb + " MB VRAM via nvidia-smi");
        }

        return new DetectionResult(Preset.VRAM_8GB,
                "Could not detect GPU VRAM (no nvidia-smi / no discrete GPU found); "
                        + "defaulting to the most conservative preset. Pass --preset to override.");
    }

    static Preset mapVramToPreset(long vramMb) {
        if (vramMb <= 9500) return Preset.VRAM_8GB;
        if (vramMb <= 13500) return Preset.VRAM_12GB;
        if (vramMb <= 17500) return Preset.VRAM_16GB;
        return Preset.VRAM_24GB;
    }

    private Long detectNvidiaVramMb() {
        try {
            Process process = new ProcessBuilder(
                    "nvidia-smi", "--query-gpu=memory.total", "--format=csv,noheader,nounits")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var in = process.inputReader(StandardCharsets.UTF_8)) {
                output = in.lines().reduce("", (a, b) -> a + "\n" + b);
            }
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                return null;
            }
            long max = -1;
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    max = Math.max(max, Long.parseLong(trimmed));
                } catch (NumberFormatException ignored) {
                    // non-numeric line (header, warning); skip
                }
            }
            return max > 0 ? max : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Raw preset JSON, from {@code configDir} on disk if one was given at construction, otherwise from the
     * {@code /presets/} classpath resources bundled into the jar. */
    public String presetJson(Preset preset) throws IOException {
        if (configDir != null) {
            Path source = configDir.resolve(preset.fileName);
            if (!Files.isRegularFile(source)) {
                throw new IOException("Preset file not found: " + source.toAbsolutePath());
            }
            return Files.readString(source, StandardCharsets.UTF_8);
        }
        String resource = "/presets/" + preset.fileName;
        try (InputStream in = InitService.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Bundled preset resource not found on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Writes the chosen preset's raw JSON to {@code target}, creating parent directories as needed. */
    public void writeConfig(Preset preset, Path target) throws IOException {
        String json = presetJson(preset);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, json, StandardCharsets.UTF_8);
    }

    /** Extracts modelName for every enabled provider whose baseUrl looks like a local Ollama endpoint. */
    public List<String> localModelNames(Path configPath) throws IOException {
        return localModelNamesFromJson(Files.readString(configPath, StandardCharsets.UTF_8));
    }

    /** Same as {@link #localModelNames(Path)} but over raw JSON text, so callers can inspect a preset before
     * it's been written to disk (e.g. to pick an Ollama base URL ahead of the write). */
    public List<String> localModelNamesFromJson(String json) throws IOException {
        JsonNode root = new ObjectMapper().readTree(json);
        List<String> models = new ArrayList<>();
        JsonNode providers = root.path("providers");
        if (providers.isArray()) {
            for (JsonNode provider : providers) {
                boolean enabled = provider.path("enabled").asBoolean(true);
                String baseUrl = provider.path("baseUrl").asText("");
                String modelName = provider.path("modelName").asText(null);
                if (enabled && modelName != null && isLocalUrl(baseUrl)) {
                    models.add(modelName);
                }
            }
        }
        return models;
    }

    private static boolean isLocalUrl(String baseUrl) {
        return baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1");
    }

    /** Returns the baseUrl of the first enabled local provider, or a sensible Ollama default. */
    public String firstLocalBaseUrl(Path configPath) throws IOException {
        return firstLocalBaseUrlFromJson(Files.readString(configPath, StandardCharsets.UTF_8));
    }

    /** Same as {@link #firstLocalBaseUrl(Path)} but over raw JSON text. */
    public String firstLocalBaseUrlFromJson(String json) throws IOException {
        JsonNode root = new ObjectMapper().readTree(json);
        for (JsonNode provider : root.path("providers")) {
            String baseUrl = provider.path("baseUrl").asText("");
            if (provider.path("enabled").asBoolean(true) && isLocalUrl(baseUrl)) {
                return baseUrl;
            }
        }
        return "http://localhost:11434/v1/";
    }

    /** Best-effort probe of a local Ollama instance's installed model tags. Returns empty list if unreachable. */
    public List<String> probeOllamaModels(String ollamaBaseUrl) {
        String tagsUrl = ollamaBaseUrl.replaceAll("/v1/?$", "") + "/api/tags";
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(tagsUrl))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return List.of();
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.body());
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

    public InitReport run(Preset preset, String detectionDetail, Path target, String ollamaBaseUrl) throws IOException {
        writeConfig(preset, target);
        List<String> required = localModelNames(target);
        List<String> installed = probeOllamaModels(ollamaBaseUrl);
        boolean reachable = !installed.isEmpty() || probeOllamaAlive(ollamaBaseUrl);
        Set<String> installedSet = new HashSet<>(installed);
        List<String> missing = new ArrayList<>();
        for (String model : required) {
            if (!installedSet.contains(model)) missing.add(model);
        }
        return new InitReport(preset, detectionDetail, target, reachable, required, installed, missing);
    }

    private boolean probeOllamaAlive(String ollamaBaseUrl) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaBaseUrl))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }
}
