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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class FileHashEmbeddingCacheTest {

    @TempDir Path tempDir;

    private static final class CountingFakeEmbeddingModel implements EmbeddingModel {
        final List<List<TextSegment>> calls = new CopyOnWriteArrayList<>();

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            calls.add(textSegments);
            List<Embedding> embeddings = textSegments.stream()
                    .map(s -> Embedding.from(new float[]{0.1f, 0.2f}))
                    .toList();
            return Response.from(embeddings, new TokenUsage(10, 0));
        }

        @Override
        public Response<Embedding> embed(String text) {
            return Response.from(Embedding.from(new float[]{0.1f, 0.2f}), new TokenUsage(5, 0));
        }
    }

    @Test
    void skipsReembeddingIdenticalFileContentHashes() throws IOException {
        Path file = tempDir.resolve("Test.java");
        Files.writeString(file, "public class Test {}");

        CountingFakeEmbeddingModel fakeModel = new CountingFakeEmbeddingModel();
        EmbeddingConfig config = new EmbeddingConfig(true, "http://fake/v1/", "model", null, 60, 10, 2, 300_000, null, null, null, 0.0);
        WorkspaceEmbeddingIndex index = new WorkspaceEmbeddingIndex(config, tempDir, fakeModel);

        index.rebuild();
        int firstCallCount = fakeModel.calls.size();
        assertThat(firstCallCount).isGreaterThan(0);

        // Second rebuild with identical file content hash should skip re-embedding
        index.rebuild();
        assertThat(fakeModel.calls.size()).isEqualTo(firstCallCount);
    }
}
