package dev.justnels.castcli.index;

import dev.justnels.castcli.config.EmbeddingConfig;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceEmbeddingIndexTest {
    @TempDir
    Path workspace;

    private final FakeEmbeddingModel embeddingModel = new FakeEmbeddingModel();

    private final EmbeddingConfig config = new EmbeddingConfig(
            true, "http://fake/v1/", "fake-embed", null, 30, 10, 2, 300_000, null, null, null, 0.0);

    private WorkspaceEmbeddingIndex newIndex() {
        return new WorkspaceEmbeddingIndex(config, workspace, embeddingModel);
    }

    @Test
    void rebuildEmbedsFilesAndSearchFindsTheMostRelevantOne() throws IOException {
        Files.writeString(workspace.resolve("Cache.java"), """
                class Cache {
                    void validateTicket(String matchmakingTicket) {
                        // validate the ticket signature before accepting it
                    }
                }
                """);
        Files.writeString(workspace.resolve("Logger.java"), """
                class Logger {
                    void log(String message) {
                        System.out.println(message);
                    }
                }
                """);

        WorkspaceEmbeddingIndex index = newIndex();
        WorkspaceEmbeddingIndex.IndexReport report = index.rebuild();

        assertThat(report.filesScanned()).isEqualTo(2);
        assertThat(report.filesEmbedded()).isEqualTo(2);
        assertThat(report.filesUnchanged()).isZero();
        assertThat(report.totalChunks()).isEqualTo(2);
        assertThat(index.indexFile()).exists();

        List<WorkspaceEmbeddingIndex.SearchHit> hits = index.search("matchmaking ticket validation", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).sourcePath()).isEqualTo("Cache.java");
    }

    @Test
    void secondRebuildSkipsEmbeddingUnchangedFiles() throws IOException {
        Files.writeString(workspace.resolve("A.java"), "class A { void a() {} }\n");
        Files.writeString(workspace.resolve("B.java"), "class B { void b() {} }\n");

        WorkspaceEmbeddingIndex index = newIndex();
        index.rebuild();
        int callsAfterFirstBuild = embeddingModel.calls.size();
        assertThat(callsAfterFirstBuild).isEqualTo(2); // one embedAll call per file's single chunk

        WorkspaceEmbeddingIndex.IndexReport secondReport = index.rebuild();

        assertThat(secondReport.filesEmbedded()).isZero();
        assertThat(secondReport.filesUnchanged()).isEqualTo(2);
        assertThat(embeddingModel.calls.size()).isEqualTo(callsAfterFirstBuild); // no new embedding calls
    }

    @Test
    void changingAFileReembedsOnlyThatFile() throws IOException {
        Path fileA = workspace.resolve("A.java");
        Files.writeString(fileA, "class A { void a() {} }\n");
        Files.writeString(workspace.resolve("B.java"), "class B { void b() {} }\n");

        WorkspaceEmbeddingIndex index = newIndex();
        index.rebuild();
        int callsBefore = embeddingModel.calls.size();

        Files.writeString(fileA, "class A { void aChanged() { /* new behavior */ } }\n");
        WorkspaceEmbeddingIndex.IndexReport report = index.rebuild();

        assertThat(report.filesEmbedded()).isEqualTo(1);
        assertThat(report.filesUnchanged()).isEqualTo(1);
        assertThat(embeddingModel.calls.size()).isEqualTo(callsBefore + 1);
    }

    @Test
    void deletingAFilePrunesItsChunksFromTheIndex() throws IOException {
        Path fileA = workspace.resolve("A.java");
        Files.writeString(fileA, "class A { void a() {} }\n");
        Files.writeString(workspace.resolve("B.java"), "class B { void b() {} }\n");

        WorkspaceEmbeddingIndex index = newIndex();
        index.rebuild();

        Files.delete(fileA);
        WorkspaceEmbeddingIndex.IndexReport report = index.rebuild();

        assertThat(report.filesRemoved()).isEqualTo(1);
        assertThat(report.filesScanned()).isEqualTo(1);
        List<WorkspaceEmbeddingIndex.SearchHit> hits = index.search("class a void a", 10);
        assertThat(hits).extracting(WorkspaceEmbeddingIndex.SearchHit::sourcePath).doesNotContain("A.java");
    }

    @Test
    void searchThrowsWhenIndexHasNeverBeenBuilt() {
        WorkspaceEmbeddingIndex index = newIndex();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> index.search("anything", 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm-harness index");
    }

    @Test
    void rebuildExcludesFilesMatchingGitIgnoreAndSecurityProfiles() throws IOException {
        Files.writeString(workspace.resolve(".gitignore"), "ignored.py\n");
        Files.writeString(workspace.resolve("ignored.py"), "print('secret')\n");
        Files.writeString(workspace.resolve("credentials.json"), "{\"api_key\": \"secret-key\"}\n");
        Files.writeString(workspace.resolve(".env"), "SECRET=true\n");
        Files.writeString(workspace.resolve("App.java"), "class App { void main() {} }\n");

        WorkspaceEmbeddingIndex index = newIndex();
        WorkspaceEmbeddingIndex.IndexReport report = index.rebuild();

        assertThat(report.filesScanned()).isEqualTo(1);
        assertThat(report.filesEmbedded()).isEqualTo(1);
        List<WorkspaceEmbeddingIndex.SearchHit> hits = index.search("class App", 10);
        assertThat(hits).extracting(WorkspaceEmbeddingIndex.SearchHit::sourcePath).containsExactly("App.java");
    }

    @Test
    void rebuildHandlesMultipleFilesWithConstrainedConcurrencyAndSubBatching() throws IOException {
        EmbeddingConfig constrainedConfig = new EmbeddingConfig(
                true, "http://fake/v1/", "fake-embed", null, 30, 2, 0, 300_000, null, null, null, 0.0, 2);

        for (int i = 0; i < 10; i++) {
            StringBuilder content = new StringBuilder();
            for (int l = 0; l < 150; l++) {
                content.append("Line ").append(l).append(" in file ").append(i).append("\n");
            }
            Files.writeString(workspace.resolve("File" + i + ".java"), content.toString());
        }

        WorkspaceEmbeddingIndex index = new WorkspaceEmbeddingIndex(constrainedConfig, workspace, embeddingModel);
        WorkspaceEmbeddingIndex.IndexReport report = index.rebuild();

        assertThat(report.filesScanned()).isEqualTo(10);
        assertThat(report.filesEmbedded()).isEqualTo(10);
        assertThat(report.totalChunks()).isGreaterThan(100);
        assertThat(embeddingModel.calls).isNotEmpty();
    }

    /** Deterministic bag-of-words embedding model: hashes each word into one of a fixed number of
     * buckets, so texts sharing vocabulary end up with similar vectors, without any network calls. */
    private static final class FakeEmbeddingModel implements EmbeddingModel {
        private static final int DIMENSION = 16;
        final List<List<String>> calls = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public int dimension() {
            return DIMENSION;
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            calls.add(textSegments.stream().map(TextSegment::text).toList());
            List<Embedding> embeddings = textSegments.stream().map(seg -> vectorFor(seg.text())).toList();
            return Response.from(embeddings, new TokenUsage(textSegments.size() * 10, 0));
        }

        private static Embedding vectorFor(String text) {
            float[] vector = new float[DIMENSION];
            for (String word : text.toLowerCase(Locale.ROOT).split("\\W+")) {
                if (word.isBlank()) {
                    continue;
                }
                vector[Math.floorMod(word.hashCode(), DIMENSION)] += 1f;
            }
            return Embedding.from(vector);
        }
    }
}

