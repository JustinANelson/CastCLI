package dev.justnels.castcli.doctor;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitServiceTest {

    @Test
    void mapsVramToTheClosestPreset() {
        assertThat(InitService.mapVramToPreset(8000)).isEqualTo(InitService.Preset.VRAM_8GB);
        assertThat(InitService.mapVramToPreset(12000)).isEqualTo(InitService.Preset.VRAM_12GB);
        assertThat(InitService.mapVramToPreset(16000)).isEqualTo(InitService.Preset.VRAM_16GB);
        assertThat(InitService.mapVramToPreset(24000)).isEqualTo(InitService.Preset.VRAM_24GB);
    }

    @Test
    void parsesPresetIdsCaseInsensitively() {
        assertThat(InitService.Preset.fromId("8GB")).isEqualTo(InitService.Preset.VRAM_8GB);
        assertThat(InitService.Preset.fromId("apple-silicon")).isEqualTo(InitService.Preset.APPLE_SILICON);
    }

    @Test
    void rejectsUnknownPresetIds() {
        assertThatThrownBy(() -> InitService.Preset.fromId("bogus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus");
    }

    @Test
    void detectPresetNeverThrowsAndAlwaysReturnsADetail() {
        InitService service = new InitService();
        InitService.DetectionResult result = service.detectPreset();
        assertThat(result.preset()).isNotNull();
        assertThat(result.detectionDetail()).isNotBlank();
    }

    @Test
    void writesPresetAndListsLocalModelsAndOllamaBaseUrl(@TempDir Path tempDir) throws Exception {
        InitService service = new InitService();
        Path target = tempDir.resolve("harness.local.json");

        service.writeConfig(InitService.Preset.VRAM_8GB, target);
        assertThat(target).exists();

        List<String> models = service.localModelNames(target);
        assertThat(models).contains("qwen2.5-coder:1.5b", "deepseek-r1:8b");

        String baseUrl = service.firstLocalBaseUrl(target);
        assertThat(baseUrl).contains("localhost");
    }

    @Test
    void reportsOllamaUnreachableWhenNothingIsListening() {
        InitService service = new InitService();
        List<String> models = service.probeOllamaModels("http://127.0.0.1:1/v1/");
        assertThat(models).isEmpty();
    }

    @Test
    void runProducesAReportEvenWhenOllamaIsUnreachable(@TempDir Path tempDir) throws Exception {
        InitService service = new InitService();
        Path target = tempDir.resolve("harness.local.json");

        InitService.InitReport report = service.run(
                InitService.Preset.VRAM_8GB, "forced for test", target, "http://127.0.0.1:1/v1/");

        assertThat(report.writtenConfigPath()).isEqualTo(target);
        assertThat(report.ollamaReachable()).isFalse();
        assertThat(report.requiredLocalModels()).isNotEmpty();
        assertThat(report.missingModels()).isEqualTo(report.requiredLocalModels());
        assertThat(Files.exists(target)).isTrue();
    }

    @Test
    void writeConfigFailsClearlyWhenPresetFileIsMissing(@TempDir Path tempDir) {
        InitService service = new InitService(tempDir.resolve("no-such-dir"));
        assertThatThrownBy(() -> service.writeConfig(InitService.Preset.VRAM_8GB, tempDir.resolve("out.json")))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void firstLocalBaseUrlFromJsonExpandsTheTemplatedPlaceholderInRawPresetJson() throws Exception {
        // Regression test: preset JSON keeps baseUrl as an unexpanded "${VAR:default}" template on disk
        // (only ConfigLoader normally expands it, when loading an already-written config). init's
        // pre-write reachability probe reads the raw preset directly, so without expansion here it hands
        // a literal "${OLLAMA_BASE_URL:...}" string to java.net.URI, which throws -- silently reported as
        // "Ollama unreachable" regardless of whether Ollama is actually running.
        InitService service = new InitService();
        String rawPresetJson = service.presetJson(InitService.Preset.VRAM_8GB);
        assertThat(rawPresetJson).contains("${OLLAMA_BASE_URL:");

        String baseUrl = service.firstLocalBaseUrlFromJson(rawPresetJson);

        assertThat(baseUrl).doesNotContain("${", "}");
        assertThat(java.net.URI.create(baseUrl)).isNotNull();
    }

    @Test
    void readsPresetsFromFilesystemWhenConfigDirIsGiven(@TempDir Path tempDir) throws Exception {
        InitService bundled = new InitService();
        Path copiedPreset = tempDir.resolve(InitService.Preset.VRAM_8GB.fileName);
        Files.writeString(copiedPreset, bundled.presetJson(InitService.Preset.VRAM_8GB));

        InitService filesystemBacked = new InitService(tempDir);
        assertThat(filesystemBacked.presetJson(InitService.Preset.VRAM_8GB))
                .isEqualTo(bundled.presetJson(InitService.Preset.VRAM_8GB));
    }

    @Test
    void matchesAmdCardNamesToTheirDocumentedPreset() {
        assertThat(InitService.matchKnownGpuName("AMD Radeon RX 6700 XT").preset())
                .isEqualTo(InitService.Preset.VRAM_12GB);
        assertThat(InitService.matchKnownGpuName("AMD Radeon RX 6600").preset())
                .isEqualTo(InitService.Preset.VRAM_8GB);
        assertThat(InitService.matchKnownGpuName("AMD Radeon RX 7900 XTX").preset())
                .isEqualTo(InitService.Preset.VRAM_24GB);
    }

    @Test
    void matchesMoreSpecificNvidiaVariantsBeforeTheGenericName() {
        // "RTX 4060 Ti" must resolve to the 16GB Ti bucket, not the generic 8GB "RTX 4060" bucket.
        assertThat(InitService.matchKnownGpuName("NVIDIA GeForce RTX 4060 Ti").preset())
                .isEqualTo(InitService.Preset.VRAM_16GB);
        assertThat(InitService.matchKnownGpuName("NVIDIA GeForce RTX 4060").preset())
                .isEqualTo(InitService.Preset.VRAM_8GB);
    }

    @Test
    void returnsNoMatchForUnknownOrBlankGpuNames() {
        assertThat(InitService.matchKnownGpuName("Intel(R) UHD Graphics 630")).isNull();
        assertThat(InitService.matchKnownGpuName(null)).isNull();
        assertThat(InitService.matchKnownGpuName("  ")).isNull();
    }

    @Test
    void familyKeyIgnoresQuantizationAndInstructSuffixes() {
        assertThat(InitService.modelFamilyKey("qwen2.5-coder:7b-instruct-q4_K_M"))
                .isEqualTo(InitService.modelFamilyKey("qwen2.5-coder:7b"))
                .isEqualTo(InitService.modelFamilyKey("qwen2.5-coder:7b-instruct-q8_0"))
                .isEqualTo("qwen2.5-coder:7b");
    }

    @Test
    void familyKeyDistinguishesDifferentSizesOfTheSameFamily() {
        assertThat(InitService.modelFamilyKey("qwen2.5-coder:7b"))
                .isNotEqualTo(InitService.modelFamilyKey("qwen2.5-coder:32b-instruct-q4_K_M"));
    }

    @Test
    void resolvesSubstitutionsOnlyForRequiredModelsWithAFamilyMatchAndNoExactInstall() {
        Map<String, String> substitutions = InitService.resolveAcceptableSubstitutions(
                List.of("qwen2.5-coder:7b-instruct-q4_K_M", "deepseek-r1:8b", "gpt-4o"),
                List.of("qwen2.5-coder:7b", "gemma4:12b"));

        // family+size match, not an exact install -> substituted
        assertThat(substitutions).containsEntry("qwen2.5-coder:7b-instruct-q4_K_M", "qwen2.5-coder:7b");
        // no installed model shares deepseek-r1's family -> left for the user to pull
        assertThat(substitutions).doesNotContainKey("deepseek-r1:8b");
        // frontier/cloud model, never a candidate for a local substitution
        assertThat(substitutions).doesNotContainKey("gpt-4o");
    }

    @Test
    void resolvesNoSubstitutionWhenTheExactTagIsAlreadyInstalled() {
        Map<String, String> substitutions = InitService.resolveAcceptableSubstitutions(
                List.of("qwen2.5-coder:7b-instruct-q4_K_M"),
                List.of("qwen2.5-coder:7b-instruct-q4_K_M"));

        assertThat(substitutions).isEmpty();
    }

    @Test
    void runSubstitutesAnAlreadyInstalledCompatibleModelIntoTheWrittenConfig(@TempDir Path tempDir) throws Exception {
        // Simulates Ollama's /api/tags reporting "qwen2.5-coder:1.5b-instruct" and "gemma4:12b" installed, neither of
        // which exactly matches the 8GB preset's "qwen2.5-coder:1.5b" / "deepseek-r1:8b", but
        // the qwen tag is an acceptable family+size stand-in for the SMALL_LOCAL tier.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            String body = "{\"models\":[{\"name\":\"qwen2.5-coder:1.5b-instruct\"},{\"name\":\"gemma4:12b\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
            InitService service = new InitService();
            Path target = tempDir.resolve("harness.local.json");

            InitService.InitReport report = service.run(InitService.Preset.VRAM_8GB, "forced for test", target, baseUrl);

            assertThat(report.substitutedModels())
                    .containsEntry("qwen2.5-coder:1.5b", "qwen2.5-coder:1.5b-instruct");
            assertThat(report.requiredLocalModels()).contains("qwen2.5-coder:1.5b-instruct");
            assertThat(report.missingModels()).containsExactly("deepseek-r1:8b");
            assertThat(service.localModelNames(target)).contains("qwen2.5-coder:1.5b-instruct");
        } finally {
            server.stop(0);
        }
    }
}
