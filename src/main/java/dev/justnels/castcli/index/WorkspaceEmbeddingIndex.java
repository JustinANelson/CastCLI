package dev.justnels.castcli.index;

import dev.justnels.castcli.config.EmbeddingConfig;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Builds and queries a persisted semantic (embedding-based) index of workspace source files, so agents can
 * find code by meaning instead of only by literal/glob match ({@link dev.justnels.castcli.tools.WorkspaceTools}).
 *
 * <p>{@link #rebuild()} is incremental: unchanged files (by content hash, stored as chunk metadata) are left
 * alone -- their existing embeddings are kept as-is and no embedding-model call is made for them -- and files
 * removed from disk since the last build have their chunks pruned. Only added/changed files pay the embedding
 * cost. The index is a single {@link InMemoryEmbeddingStore}, serialized to {@link EmbeddingConfig#indexPath()}.
 */
public final class WorkspaceEmbeddingIndex {
    private static final String SOURCE_KEY = "source";
    private static final String HASH_KEY = "contentHash";
    private static final String START_LINE_KEY = "startLine";
    private static final String END_LINE_KEY = "endLine";

    private final EmbeddingConfig config;
    private final Path workspaceRoot;
    private final Path indexFile;
    private final EmbeddingModel embeddingModel;
    private volatile Embedding cachedZeroEmbedding;
    private final ExecutorService backgroundRebuildExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "workspace-index-background-rebuild");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean backgroundRebuildInProgress = new AtomicBoolean(false);

    public WorkspaceEmbeddingIndex(EmbeddingConfig config, Path workspaceRoot, EmbeddingModel embeddingModel) {
        this.config = config;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.indexFile = this.workspaceRoot.resolve(config.indexPath()).normalize();
        this.embeddingModel = embeddingModel;
    }

    public record IndexReport(
            int filesScanned, int filesEmbedded, int filesUnchanged, int filesRemoved,
            int totalChunks, long inputTokensUsed, double estimatedCostUsd, long durationMs) {
    }

    public record SearchHit(String sourcePath, int startLine, int endLine, double score, String text) {
    }

    public Path indexFile() {
        return indexFile;
    }

    private record FileProcessingTask(
            String relativePath, boolean unchanged, List<TextSegment> segments,
            Response<List<Embedding>> embeddingResponse, int existingChunkCount) { }

    /** Rebuilds the index in place: embeds new/changed files, reuses embeddings for unchanged ones, and
     * prunes chunks for files no longer present on disk. Safe to call repeatedly (e.g. after every edit). */
    public IndexReport rebuild() throws IOException {
        long startTime = System.currentTimeMillis();
        InMemoryEmbeddingStore<TextSegment> store = Files.isRegularFile(indexFile)
                ? InMemoryEmbeddingStore.fromFile(indexFile)
                : new InMemoryEmbeddingStore<>();

        Set<String> previousSources = store.isEmpty() ? Set.of() : allDistinctSources(store);
        java.util.Map<String, List<TextSegment>> existingBySource = new java.util.HashMap<>();
        if (!store.isEmpty()) {
            for (TextSegment segment : scanWithFilter(store, null)) {
                String source = segment.metadata().getString(SOURCE_KEY);
                if (source != null) {
                    existingBySource.computeIfAbsent(source, k -> new ArrayList<>()).add(segment);
                }
            }
        }

        List<Path> files = collectIncludedFiles();
        Set<String> currentSources = ConcurrentHashMap.newKeySet();

        int filesEmbedded = 0;
        int filesUnchanged = 0;
        int totalChunks = 0;
        long inputTokensUsed = 0;

        Semaphore semaphore = new Semaphore(config.maxConcurrency());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FileProcessingTask>> futures = new ArrayList<>();

            for (Path file : files) {
                futures.add(executor.submit(() -> {
                    String relativePath = toRelativePath(file);
                    currentSources.add(relativePath);

                    String hash;
                    try {
                        hash = FastFileHasher.hashFile(file);
                    } catch (IOException | RuntimeException notText) {
                        return null; // skip unreadable/binary files
                    }

                    List<TextSegment> existing = existingBySource.getOrDefault(relativePath, List.of());
                    if (!existing.isEmpty() && hash.equals(existing.get(0).metadata().getString(HASH_KEY))) {
                        return new FileProcessingTask(relativePath, true, List.of(), null, existing.size());
                    }

                    List<String> lines;
                    try {
                        lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    } catch (IOException | RuntimeException notText) {
                        return null; // skip unreadable/binary files
                    }

                    List<TextSegment> segments = chunkFile(relativePath, lines, hash);
                    if (segments.isEmpty()) {
                        return new FileProcessingTask(relativePath, false, List.of(), null, 0);
                    }

                    semaphore.acquire();
                    try {
                        Response<List<Embedding>> response = embedSegmentsInBatches(segments);
                        return new FileProcessingTask(relativePath, false, segments, response, 0);
                    } finally {
                        semaphore.release();
                    }
                }));
            }

            for (Future<FileProcessingTask> future : futures) {
                FileProcessingTask taskResult;
                try {
                    taskResult = future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Indexing interrupted", e);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException re) throw re;
                    throw new IOException("Failed to process file during indexing", e.getCause());
                }

                if (taskResult == null) continue;

                if (taskResult.unchanged()) {
                    filesUnchanged++;
                    totalChunks += taskResult.existingChunkCount();
                } else if (!taskResult.segments().isEmpty()) {
                    store.removeAll(sourceFilter(taskResult.relativePath()));
                    store.addAll(taskResult.embeddingResponse().content(), taskResult.segments());
                    filesEmbedded++;
                    totalChunks += taskResult.segments().size();
                    if (taskResult.embeddingResponse().tokenUsage() != null) {
                        inputTokensUsed += taskResult.embeddingResponse().tokenUsage().inputTokenCount();
                    }
                }
            }
        }

        Set<String> removedSources = new LinkedHashSet<>(previousSources);
        removedSources.removeAll(currentSources);
        for (String removed : removedSources) {
            store.removeAll(sourceFilter(removed));
        }

        Files.createDirectories(indexFile.getParent());
        store.serializeToFile(indexFile);

        long duration = System.currentTimeMillis() - startTime;
        return new IndexReport(files.size(), filesEmbedded, filesUnchanged, removedSources.size(),
                totalChunks, inputTokensUsed, config.estimatedCostUsd(inputTokensUsed), duration);
    }

    public static final double DEFAULT_MIN_SCORE = 0.0;

    /** Semantically searches the persisted index; throws if {@link #rebuild()} has never been run. */
    public List<SearchHit> search(String query, int maxResults) {
        return search(query, maxResults, DEFAULT_MIN_SCORE);
    }

    /** Semantically searches the persisted index with an explicit minimum similarity score threshold. */
    public List<SearchHit> search(String query, int maxResults, double minScore) {
        if (!Files.isRegularFile(indexFile)) {
            throw new IllegalStateException(
                    "No semantic index found at " + indexFile + ". Run 'llm-harness index' first.");
        }
        InMemoryEmbeddingStore<TextSegment> store = InMemoryEmbeddingStore.fromFile(indexFile);
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
        EmbeddingSearchResult<TextSegment> result = store.search(request);
        return result.matches().stream()
                .map(match -> new SearchHit(
                        match.embedded().metadata().getString(SOURCE_KEY),
                        intMetadata(match.embedded().metadata(), START_LINE_KEY),
                        intMetadata(match.embedded().metadata(), END_LINE_KEY),
                        match.score(),
                        match.embedded().text()))
                .toList();
    }

    /** Semantically searches the persisted index. If no index file exists yet, performs a synchronous
     * incremental rebuild first (there's nothing to search otherwise). If an index already exists but looks
     * stale (some included file was modified after the index was last written), the search is served
     * immediately against the existing index while a rebuild is kicked off on a background thread -- so a
     * search never blocks on an unbounded embedding pass over a large batch of changed files. Results may
     * therefore lag behind disk by one rebuild; subsequent calls pick up the refreshed index once it lands. */
    public List<SearchHit> searchOrAutoRebuild(String query, int maxResults) {
        if (!Files.isRegularFile(indexFile)) {
            try {
                rebuild();
            } catch (IOException e) {
                throw new IllegalStateException("Auto-rebuilding index failed: " + e.getMessage(), e);
            }
        } else {
            triggerBackgroundRebuildIfStale();
        }
        return search(query, maxResults);
    }

    /** Kicks off an incremental {@link #rebuild()} on a background thread if the index looks stale, unless a
     * background rebuild is already running (in which case this is a no-op -- the running rebuild will pick
     * up whatever is on disk when it starts scanning). */
    private void triggerBackgroundRebuildIfStale() {
        if (!isStale() || !backgroundRebuildInProgress.compareAndSet(false, true)) {
            return;
        }
        backgroundRebuildExecutor.submit(() -> {
            try {
                rebuild();
            } catch (IOException | RuntimeException e) {
                System.err.println("Background workspace index rebuild failed: " + e.getMessage());
            } finally {
                backgroundRebuildInProgress.set(false);
            }
        });
    }

    /** Cheap staleness check: walks included files and compares their mtimes against the index file's mtime,
     * without reading file contents. This is the same file set {@link #rebuild()} would scan, but skipping
     * the read+hash+embed cost makes it safe to call on every search. */
    private boolean isStale() {
        try {
            long indexModified = Files.getLastModifiedTime(indexFile).toMillis();
            for (Path file : collectIncludedFiles()) {
                if (Files.getLastModifiedTime(file).toMillis() > indexModified) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }


    private List<Path> collectIncludedFiles() throws IOException {
        IndexerIgnoreConfig ignoreConfig = IndexerIgnoreConfig.load(workspaceRoot);

        List<String> combinedExcludes = new ArrayList<>(config.excludeGlobs());
        for (String pattern : ignoreConfig.excludeGlobs()) {
            if (!combinedExcludes.contains(pattern)) {
                combinedExcludes.add(pattern);
            }
        }

        List<PathMatcher> includeMatchers = buildMatchers(config.includeGlobs());
        List<PathMatcher> excludeMatchers = buildMatchers(combinedExcludes);

        GitIgnoreMatcher gitIgnoreMatcher = ignoreConfig.includeGitIgnore()
                ? GitIgnoreMatcher.load(workspaceRoot)
                : null;

        try (Stream<Path> paths = Files.walk(workspaceRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(this::isSmallEnough)
                    .filter(path -> {
                        Path relative = workspaceRoot.relativize(path);
                        String relativeStr = relative.toString().replace('\\', '/');
                        if (gitIgnoreMatcher != null && gitIgnoreMatcher.isIgnored(relativeStr, false)) {
                            return false;
                        }
                        return includeMatchers.stream().anyMatch(m -> m.matches(relative))
                                && excludeMatchers.stream().noneMatch(m -> m.matches(relative));
                    })
                    .toList();
        }
    }

    /** For each {@code **}/-prefixed glob, also builds a matcher for the bare suffix, since
     * {@code java.nio}'s "**" requires at least one path separator and so never matches a file
     * directly under the workspace root (e.g. {@code Cache.java} rather than {@code src/Cache.java}). */
    private static List<PathMatcher> buildMatchers(List<String> globs) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String glob : globs) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
            if (glob.startsWith("**/")) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3)));
            }
        }
        return matchers;
    }

    private boolean isSmallEnough(Path path) {
        try {
            return Files.size(path) <= config.maxFileBytes();
        } catch (IOException ignored) {
            return false;
        }
    }

    private String toRelativePath(Path file) {
        return workspaceRoot.relativize(file).toString().replace('\\', '/');
    }

    private List<TextSegment> chunkFile(String relativePath, List<String> lines, String hash) {
        int window = config.chunkLines();
        int overlap = config.chunkOverlapLines();
        List<TextSegment> segments = new ArrayList<>();
        int start = 0;
        while (start < lines.size()) {
            int end = Math.min(start + window, lines.size());
            String text = String.join("\n", lines.subList(start, end)).trim();
            if (!text.isEmpty()) {
                Metadata metadata = new Metadata(java.util.Map.of(
                        SOURCE_KEY, relativePath,
                        HASH_KEY, hash,
                        START_LINE_KEY, start + 1,
                        END_LINE_KEY, end));
                segments.add(TextSegment.from(text, metadata));
            }
            if (end == lines.size()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return segments;
    }

    private Embedding zeroEmbedding() {
        Embedding cached = cachedZeroEmbedding;
        if (cached == null) {
            cached = Embedding.from(new float[embeddingModel.dimension()]);
            cachedZeroEmbedding = cached;
        }
        return cached;
    }

    /** Fetches every chunk for a given file's metadata without a real similarity search: a filter-only,
     * zero-vector, {@code minScore=0} query returns every entry the filter admits regardless of score. */
    private List<TextSegment> findBySource(InMemoryEmbeddingStore<TextSegment> store, String relativePath) {
        return scanWithFilter(store, sourceFilter(relativePath));
    }

    private Set<String> allDistinctSources(InMemoryEmbeddingStore<TextSegment> store) {
        Set<String> sources = new LinkedHashSet<>();
        for (TextSegment segment : scanWithFilter(store, null)) {
            String source = segment.metadata().getString(SOURCE_KEY);
            if (source != null) {
                sources.add(source);
            }
        }
        return sources;
    }

    private List<TextSegment> scanWithFilter(InMemoryEmbeddingStore<TextSegment> store, Filter filter) {
        if (store.isEmpty()) {
            return List.of();
        }
        EmbeddingSearchRequest.EmbeddingSearchRequestBuilder builder = EmbeddingSearchRequest.builder()
                .queryEmbedding(zeroEmbedding())
                .maxResults(Integer.MAX_VALUE)
                .minScore(0.0);
        if (filter != null) {
            builder.filter(filter);
        }
        return store.search(builder.build()).matches().stream()
                .map(dev.langchain4j.store.embedding.EmbeddingMatch::embedded)
                .toList();
    }

    private static Filter sourceFilter(String relativePath) {
        return MetadataFilterBuilder.metadataKey(SOURCE_KEY).isEqualTo(relativePath);
    }

    private static int intMetadata(Metadata metadata, String key) {
        Integer value = metadata.getInteger(key);
        return value == null ? 0 : value;
    }

    private static final int MAX_EMBEDDING_BATCH_SIZE = 64;

    private Response<List<Embedding>> embedSegmentsInBatches(List<TextSegment> segments) {
        if (segments.size() <= MAX_EMBEDDING_BATCH_SIZE) {
            return embeddingModel.embedAll(segments);
        }

        List<Embedding> allEmbeddings = new ArrayList<>(segments.size());
        int totalInputTokens = 0;
        boolean hasTokenUsage = false;

        for (int i = 0; i < segments.size(); i += MAX_EMBEDDING_BATCH_SIZE) {
            List<TextSegment> batch = segments.subList(i, Math.min(i + MAX_EMBEDDING_BATCH_SIZE, segments.size()));
            Response<List<Embedding>> batchResponse = embeddingModel.embedAll(batch);
            allEmbeddings.addAll(batchResponse.content());
            if (batchResponse.tokenUsage() != null && batchResponse.tokenUsage().inputTokenCount() != null) {
                totalInputTokens += batchResponse.tokenUsage().inputTokenCount();
                hasTokenUsage = true;
            }
        }

        TokenUsage tokenUsage = hasTokenUsage ? new TokenUsage(totalInputTokens, 0) : null;
        return Response.from(allEmbeddings, tokenUsage);
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

