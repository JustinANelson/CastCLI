package dev.justnels.castcli.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AtomicFileWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void replacesTargetAndPreservesPreviousVersionAsBackup() throws Exception {
        Path target = tempDir.resolve("state.json");
        AtomicFileWriter.write(target, "first".getBytes(StandardCharsets.UTF_8));
        AtomicFileWriter.write(target, "second".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(target)).isEqualTo("second");
        assertThat(Files.readString(AtomicFileWriter.backupPath(target))).isEqualTo("first");
    }

    @Test
    void successfulWriteLeavesNoTemporaryFiles() throws Exception {
        Path target = tempDir.resolve("state.json");
        AtomicFileWriter.write(target, new byte[0]);

        try (var files = Files.list(tempDir)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".tmp")).toList()).isEmpty();
        }
    }
}
