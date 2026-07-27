package dev.justnels.castcli.tools;

import dev.justnels.castcli.config.EmbeddingConfig;
import dev.justnels.castcli.index.WorkspaceEmbeddingIndex;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticSearchToolsTest {
    @TempDir
    Path workspace;

    @Test
    void returnsFormattedHitsFromTheIndex() throws IOException {
        Files.writeString(workspace.resolve("Ticket.java"), "class Ticket { void validate() { /* checks signature */ } }\n");

        EmbeddingConfig config = new EmbeddingConfig(
                true, "http://fake/v1/", "fake-embed", null, 30, 10, 2, 300_000, null, null, null, 0.0);
        WorkspaceEmbeddingIndex index = new WorkspaceEmbeddingIndex(config, workspace, new HashingEmbeddingModel());
        index.rebuild();
        SemanticSearchTools tools = new SemanticSearchTools(index);

        List<String> hits = tools.semanticSearchWorkspace("ticket validation", 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0)).contains("Ticket.java").contains("score");
    }

    @Test
    void rejectsMaxResultsOutOfRange() {
        EmbeddingConfig config = new EmbeddingConfig(
                true, "http://fake/v1/", "fake-embed", null, 30, 10, 2, 300_000, null, null, null, 0.0);
        WorkspaceEmbeddingIndex index = new WorkspaceEmbeddingIndex(config, workspace, new HashingEmbeddingModel());
        SemanticSearchTools tools = new SemanticSearchTools(index);

        assertThatThrownBy(() -> tools.semanticSearchWorkspace("query", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.semanticSearchWorkspace("query", 51)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class HashingEmbeddingModel implements EmbeddingModel {
        @Override
        public int dimension() {
            return 16;
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            return Response.from(textSegments.stream().map(seg -> vectorFor(seg.text())).toList());
        }

        private static Embedding vectorFor(String text) {
            float[] vector = new float[16];
            for (String word : text.toLowerCase(Locale.ROOT).split("\\W+")) {
                if (!word.isBlank()) {
                    vector[Math.floorMod(word.hashCode(), 16)] += 1f;
                }
            }
            return Embedding.from(vector);
        }
    }
}

