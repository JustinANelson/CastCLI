package dev.justnels.castcli.index;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages workspace embedding persistence as multiple sharded store files.
 *
 * <p>Instead of serializing the entire vector store into a single monolithic JSON file on every edit,
 * {@code ShardedEmbeddingStore} partitions entries into bucketed shard files based on each file segment's
 * source path hash. During incremental rebuilds, only dirty shards containing added, modified, or removed
 * source files are re-serialized to disk, eliminating $O(N)$ write amplification.
 */
public final class ShardedEmbeddingStore {
    private static final String SOURCE_KEY = "source";
    public static final int DEFAULT_SHARD_COUNT = 16;

    private final Path primaryIndexFile;
    private final Path shardsDir;
    private final int shardCount;
    private final Map<Integer, InMemoryEmbeddingStore<TextSegment>> shards = new HashMap<>();
    private final Set<Integer> dirtyShards = new HashSet<>();
    private volatile int knownVectorDimension = -1;

    public ShardedEmbeddingStore(Path primaryIndexFile) {
        this(primaryIndexFile, DEFAULT_SHARD_COUNT);
    }

    public ShardedEmbeddingStore(Path primaryIndexFile, int shardCount) {
        this.primaryIndexFile = primaryIndexFile.toAbsolutePath().normalize();
        this.shardsDir = computeShardsDir(this.primaryIndexFile);
        this.shardCount = Math.max(1, shardCount);
        for (int i = 0; i < this.shardCount; i++) {
            shards.put(i, new InMemoryEmbeddingStore<>());
        }
    }

    private static final int[] CANDIDATE_DIMENSIONS = new int[]{1024, 1536, 16, 8, 2, 4, 768, 384, 512, 4096, 2048, 128, 256, 32, 64, 3072};

    public int vectorDimension() {
        if (knownVectorDimension > 0) {
            return knownVectorDimension;
        }
        for (InMemoryEmbeddingStore<TextSegment> shard : shards.values()) {
            if (!shard.isEmpty()) {
                for (int dim : CANDIDATE_DIMENSIONS) {
                    try {
                        List<EmbeddingMatch<TextSegment>> matches = shard.search(EmbeddingSearchRequest.builder()
                                .queryEmbedding(Embedding.from(new float[dim]))
                                .maxResults(1)
                                .minScore(0.0)
                                .build()).matches();
                        if (!matches.isEmpty() && matches.get(0).embedding() != null && matches.get(0).embedding().vector() != null) {
                            knownVectorDimension = matches.get(0).embedding().vector().length;
                            return knownVectorDimension;
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        return knownVectorDimension > 0 ? knownVectorDimension : 1024;
    }

    public static Path computeShardsDir(Path primaryIndexFile) {
        String fileName = primaryIndexFile.getFileName().toString();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        return primaryIndexFile.getParent().resolve(baseName + ".shards");
    }

    public Path primaryIndexFile() {
        return primaryIndexFile;
    }

    public Path shardsDir() {
        return shardsDir;
    }

    public int shardCount() {
        return shardCount;
    }

    public Set<Integer> dirtyShards() {
        return Set.copyOf(dirtyShards);
    }

    /**
     * Loads a sharded embedding store from disk. If a sharded directory exists, reads each shard file.
     * If only a single legacy index file exists, loads it and partitions it into shards.
     */
    public static ShardedEmbeddingStore load(Path primaryIndexFile) {
        return load(primaryIndexFile, DEFAULT_SHARD_COUNT);
    }

    public static ShardedEmbeddingStore load(Path primaryIndexFile, int shardCount) {
        ShardedEmbeddingStore store = new ShardedEmbeddingStore(primaryIndexFile, shardCount);
        Path shardsDir = store.shardsDir();

        if (Files.isDirectory(shardsDir)) {
            for (int i = 0; i < store.shardCount; i++) {
                Path shardFile = shardsDir.resolve(shardFileName(i));
                if (Files.isRegularFile(shardFile)) {
                    store.shards.put(i, InMemoryEmbeddingStore.fromFile(shardFile));
                }
            }
        } else if (Files.isRegularFile(primaryIndexFile)) {
            // Backward-compatible migration from legacy monolithic index
            InMemoryEmbeddingStore<TextSegment> legacyStore = InMemoryEmbeddingStore.fromFile(primaryIndexFile);
            if (!legacyStore.isEmpty()) {
                List<EmbeddingMatch<TextSegment>> matches = List.of();
                int[] candidateDimensions = new int[]{1024, 1536, 768, 384, 512, 4096, 2048, 128, 256, 3072};
                for (int dim : candidateDimensions) {
                    try {
                        matches = legacyStore.search(EmbeddingSearchRequest.builder()
                                .queryEmbedding(Embedding.from(new float[dim]))
                                .maxResults(Integer.MAX_VALUE)
                                .minScore(0.0)
                                .build()).matches();
                        break;
                    } catch (IllegalArgumentException ignored) {
                        // Dimension mismatch; try next candidate dimension
                    }
                }

                for (EmbeddingMatch<TextSegment> match : matches) {
                    TextSegment segment = match.embedded();
                    Embedding embedding = match.embedding();
                    String source = segment.metadata().getString(SOURCE_KEY);
                    int shardIndex = store.shardIndexFor(source);
                    store.shards.get(shardIndex).add(embedding, segment);
                    store.dirtyShards.add(shardIndex);
                }
            }
        }
        store.vectorDimension();
        return store;
    }

    /**
     * Checks whether all shards in the store are empty.
     */
    public boolean isEmpty() {
        return shards.values().stream().allMatch(InMemoryEmbeddingStore::isEmpty);
    }

    /**
     * Returns the target shard index for a given relative source path.
     */
    public int shardIndexFor(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return 0;
        }
        return Math.abs(sourcePath.hashCode()) % shardCount;
    }

    /**
     * Adds embeddings and text segments to their corresponding path-partitioned shards.
     */
    public void addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        if (embeddings == null || segments == null || embeddings.size() != segments.size()) {
            throw new IllegalArgumentException("Embeddings and segments must be non-null and equal size");
        }
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            Embedding embedding = embeddings.get(i);
            if (embedding != null && embedding.vector() != null && embedding.vector().length > 0) {
                this.knownVectorDimension = embedding.vector().length;
            }
            String source = segment.metadata().getString(SOURCE_KEY);
            int shardIndex = shardIndexFor(source);

            shards.get(shardIndex).add(embedding, segment);
            dirtyShards.add(shardIndex);
        }
    }

    /**
     * Removes text segments matching the given filter across all shards.
     */
    public void removeAll(Filter filter) {
        if (filter == null) {
            return;
        }
        for (Map.Entry<Integer, InMemoryEmbeddingStore<TextSegment>> entry : shards.entrySet()) {
            int shardIndex = entry.getKey();
            InMemoryEmbeddingStore<TextSegment> shardStore = entry.getValue();
            if (!shardStore.isEmpty()) {
                shardStore.removeAll(filter);
                dirtyShards.add(shardIndex);
            }
        }
    }

    /**
     * Queries all shards and returns a merged similarity search result.
     */
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        List<EmbeddingMatch<TextSegment>> allMatches = new ArrayList<>();
        for (InMemoryEmbeddingStore<TextSegment> shardStore : shards.values()) {
            if (!shardStore.isEmpty()) {
                EmbeddingSearchResult<TextSegment> shardResult = shardStore.search(request);
                allMatches.addAll(shardResult.matches());
            }
        }

        allMatches.sort(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed());
        if (allMatches.size() > request.maxResults()) {
            allMatches = allMatches.subList(0, request.maxResults());
        }

        return new EmbeddingSearchResult<>(allMatches);
    }

    /**
     * Saves dirty shards to disk and updates the primary index file timestamp.
     */
    public void save() throws IOException {
        if (primaryIndexFile.getParent() != null) {
            Files.createDirectories(primaryIndexFile.getParent());
        }
        Files.createDirectories(shardsDir);

        for (int shardIndex : dirtyShards) {
            Path shardFile = shardsDir.resolve(shardFileName(shardIndex));
            InMemoryEmbeddingStore<TextSegment> shardStore = shards.get(shardIndex);
            if (shardStore.isEmpty()) {
                Files.deleteIfExists(shardFile);
            } else {
                shardStore.serializeToFile(shardFile);
            }
        }
        dirtyShards.clear();

        // Touch or write primary index file so lastModifiedTime reflects the index build
        if (!Files.exists(primaryIndexFile)) {
            Files.writeString(primaryIndexFile, "{\"sharded\": true, \"shardCount\": " + shardCount + "}");
        } else {
            Files.setLastModifiedTime(primaryIndexFile, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
        }
    }

    private static String shardFileName(int index) {
        return String.format("shard-%02d.json", index);
    }
}
