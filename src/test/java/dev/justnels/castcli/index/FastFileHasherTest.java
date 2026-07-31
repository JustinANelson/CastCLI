package dev.justnels.castcli.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FastFileHasherTest {
    @TempDir
    Path tempDir;

    @Test
    void hashFileProducesConsistentDigestForLFAndCRLF() throws IOException {
        Path lfFile = tempDir.resolve("lf.txt");
        Path crlfFile = tempDir.resolve("crlf.txt");

        Files.writeString(lfFile, "class Cache {\n    void validate() {}\n}\n");
        Files.writeString(crlfFile, "class Cache {\r\n    void validate() {}\r\n}\r\n");

        String lfHash = FastFileHasher.hashFile(lfFile);
        String crlfHash = FastFileHasher.hashFile(crlfFile);

        assertThat(lfHash).isNotEmpty();
        assertThat(lfHash).isEqualTo(crlfHash);
    }

    @Test
    void hashFileHandlesEmptyFile() throws IOException {
        Path emptyFile = tempDir.resolve("empty.txt");
        Files.createFile(emptyFile);

        String hash = FastFileHasher.hashFile(emptyFile);

        assertThat(hash).isNotNull();
        assertThat(hash.length()).isEqualTo(64);
    }
}
