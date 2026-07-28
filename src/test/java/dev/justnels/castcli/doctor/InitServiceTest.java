package dev.justnels.castcli.doctor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        assertThat(models).contains("qwen2.5-coder:7b-instruct-q4_K_M", "deepseek-r1:8b");

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
    void readsPresetsFromFilesystemWhenConfigDirIsGiven(@TempDir Path tempDir) throws Exception {
        InitService bundled = new InitService();
        Path copiedPreset = tempDir.resolve(InitService.Preset.VRAM_8GB.fileName);
        Files.writeString(copiedPreset, bundled.presetJson(InitService.Preset.VRAM_8GB));

        InitService filesystemBacked = new InitService(tempDir);
        assertThat(filesystemBacked.presetJson(InitService.Preset.VRAM_8GB))
                .isEqualTo(bundled.presetJson(InitService.Preset.VRAM_8GB));
    }
}
