package dev.justnels.castcli.index;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShardedEmbeddingStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void addAllPartitionsEntriesAndMarksShardsDirty() {
        Path indexFile = tempDir.resolve("workspace.json");
        ShardedEmbeddingStore store = new ShardedEmbeddingStore(indexFile, 4);

        TextSegment seg1 = TextSegment.from("code chunk 1", new Metadata(Map.of("source", "src/Main.java")));
        TextSegment seg2 = TextSegment.from("code chunk 2", new Metadata(Map.of("source", "src/Utils.java")));
        Embedding emb1 = Embedding.from(new float[]{1.0f, 0.0f});
        Embedding emb2 = Embedding.from(new float[]{0.0f, 1.0f});

        store.addAll(List.of(emb1, emb2), List.of(seg1, seg2));

        assertThat(store.isEmpty()).isFalse();
        assertThat(store.dirtyShards()).isNotEmpty();
    }

    @Test
    void saveWritesOnlyDirtyShardsToDisk() throws IOException {
        Path indexFile = tempDir.resolve("workspace.json");
        ShardedEmbeddingStore store = new ShardedEmbeddingStore(indexFile, 4);

        TextSegment seg = TextSegment.from("class Cache {}", new Metadata(Map.of("source", "src/Cache.java")));
        Embedding emb = Embedding.from(new float[]{0.5f, 0.5f});
        store.addAll(List.of(emb), List.of(seg));

        int dirtyShardIndex = store.shardIndexFor("src/Cache.java");
        assertThat(store.dirtyShards()).containsExactly(dirtyShardIndex);

        store.save();

        assertThat(store.dirtyShards()).isEmpty();
        assertThat(Files.exists(store.shardsDir())).isTrue();
        assertThat(Files.exists(indexFile)).isTrue();

        // Reload and verify
        ShardedEmbeddingStore loadedStore = ShardedEmbeddingStore.load(indexFile, 4);
        assertThat(loadedStore.isEmpty()).isFalse();

        EmbeddingSearchResult<TextSegment> result = loadedStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(emb)
                .maxResults(5)
                .build());
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).embedded().text()).isEqualTo("class Cache {}");
    }

    @Test
    void searchMergesAndRanksMatchesAcrossAllShards() {
        Path indexFile = tempDir.resolve("workspace.json");
        ShardedEmbeddingStore store = new ShardedEmbeddingStore(indexFile, 4);

        TextSegment seg1 = TextSegment.from("chunk A", new Metadata(Map.of("source", "A.java")));
        TextSegment seg2 = TextSegment.from("chunk B", new Metadata(Map.of("source", "B.java")));
        Embedding emb1 = Embedding.from(new float[]{0.9f, 0.1f});
        Embedding emb2 = Embedding.from(new float[]{0.1f, 0.9f});

        store.addAll(List.of(emb1, emb2), List.of(seg1, seg2));

        EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1.0f, 0.0f}))
                .maxResults(2)
                .build());

        assertThat(result.matches()).hasSize(2);
        assertThat(result.matches().get(0).embedded().text()).isEqualTo("chunk A");
    }

    @Test
    void removeAllClearsFilteredEntriesAndMarksShardsDirty() throws IOException {
        Path indexFile = tempDir.resolve("workspace.json");
        ShardedEmbeddingStore store = new ShardedEmbeddingStore(indexFile, 4);

        TextSegment seg1 = TextSegment.from("chunk 1", new Metadata(Map.of("source", "A.java")));
        TextSegment seg2 = TextSegment.from("chunk 2", new Metadata(Map.of("source", "B.java")));
        store.addAll(List.of(Embedding.from(new float[]{1.0f}), Embedding.from(new float[]{1.0f})), List.of(seg1, seg2));
        store.save();

        store.removeAll(MetadataFilterBuilder.metadataKey("source").isEqualTo("A.java"));
        assertThat(store.dirtyShards()).isNotEmpty();

        EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1.0f}))
                .maxResults(5)
                .build());

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).embedded().metadata().getString("source")).isEqualTo("B.java");
    }
}
