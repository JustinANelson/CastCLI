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

    /** Cached deserialization of {@link #indexFile}, keyed by its last-modified time, so a long-lived
     * process (e.g. the MCP server) doesn't re-parse a potentially multi-megabyte JSON index on every
     * search call. */
    private volatile CachedStore cachedStore;

    private static final long STALE_CHECK_MIN_INTERVAL_MS = 2000;
    /** Throttles {@link #isStale()}, which walks the whole workspace; without this, every search call
     * in quick succession (e.g. several agent tool calls in a row) repeats that walk for no benefit. */
    private volatile long lastStaleCheckMillis = Long.MIN_VALUE;
    private volatile boolean lastStaleResult;

    public WorkspaceEmbeddingIndex(EmbeddingConfig config, Path workspaceRoot, EmbeddingModel embeddingModel) {
        this.config = config;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.indexFile = this.workspaceRoot.resolve(config.indexPath()).normalize();
        this.embeddingModel = embeddingModel;
    }

    public record IndexReport(
            int filesScanned, int filesEmbedded, int filesUnchanged, int filesRemoved, int filesFailed,
            List<String> failedFiles,
            int totalChunks, long inputTokensUsed, double estimatedCostUsd, long durationMs) {
    }

    public record SearchHit(String sourcePath, int startLine, int endLine, double score, String text) {
    }

    /** Reports progress during {@link #rebuild(ProgressListener)} so long-running indexing runs (large
     * repositories, slow/cold embedding backends) can show the caller something is happening instead of
     * appearing to hang. All methods are called from whichever thread completes the corresponding work,
     * so implementations must be thread-safe. */
    public interface ProgressListener {
        ProgressListener NO_OP = new ProgressListener() { };

        /** Called once, right after the file scan completes and before any embedding begins. */
        default void onScanComplete(int totalFiles) { }

        /** Called after each file finishes processing (embedded, unchanged, or failed). */
        default void onFileProcessed(int completed, int total, String relativePath, boolean failed) { }
    }

    public Path indexFile() {
        return indexFile;
    }

    private record CachedStore(InMemoryEmbeddingStore<TextSegment> store, long mtimeMillis) { }

    private record FileProcessingTask(
            String relativePath, boolean unchanged, boolean failed, String failureMessage,
            List<TextSegment> segments, Response<List<Embedding>> embeddingResponse, int existingChunkCount) { }

    /** Rebuilds the index in place: embeds new/changed files, reuses embeddings for unchanged ones, and
     * prunes chunks for files no longer present on disk. Safe to call repeatedly (e.g. after every edit). */
    public IndexReport rebuild() throws IOException {
        return rebuild(ProgressListener.NO_OP);
    }

    /** Same as {@link #rebuild()}, additionally reporting progress via {@code listener}. A single file's
     * embedding failure (e.g. an HTTP timeout against a slow/cold local backend) does not abort the whole
     * run: that file is skipped and counted in {@link IndexReport#filesFailed()} so every other file's work
     * is still embedded, persisted, and not lost. Skipped files are retried automatically on the next
     * {@code rebuild()} call, since they aren't recorded as unchanged. */
    public IndexReport rebuild(ProgressListener listener) throws IOException {
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
        listener.onScanComplete(files.size());

        int filesEmbedded = 0;
        int filesUnchanged = 0;
        int totalChunks = 0;
        long inputTokensUsed = 0;
        List<String> failedFiles = new ArrayList<>();

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
                        return new FileProcessingTask(relativePath, true, false, null, List.of(), null, existing.size());
                    }

                    List<String> lines;
                    try {
                        lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    } catch (IOException | RuntimeException notText) {
                        return null; // skip unreadable/binary files
                    }

                    List<TextSegment> segments = chunkFile(relativePath, lines, hash);
                    if (segments.isEmpty()) {
                        return new FileProcessingTask(relativePath, false, false, null, List.of(), null, 0);
                    }

                    semaphore.acquire();
                    try {
                        Response<List<Embedding>> response = embedSegmentsInBatches(segments);
                        return new FileProcessingTask(relativePath, false, false, null, segments, response, 0);
                    } catch (RuntimeException embeddingFailure) {
                        // A single file's embedding failure (e.g. an HTTP timeout against a slow/cold
                        // local backend) must not abort the whole run and lose every other file's work --
                        // skip it and let the caller retry on the next rebuild().
                        return new FileProcessingTask(relativePath, false, true,
                                embeddingFailure.getClass().getSimpleName() + ": " + embeddingFailure.getMessage(),
                                List.of(), null, 0);
                    } finally {
                        semaphore.release();
                    }
                }));
            }

            int completed = 0;
            for (Future<FileProcessingTask> future : futures) {
                FileProcessingTask taskResult;
                try {
                    taskResult = future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Indexing interrupted", e);
                } catch (ExecutionException e) {
                    // Should be unreachable now that embedding failures are caught above, but treat any
                    // other unexpected per-file failure the same way rather than aborting the whole run.
                    completed++;
                    failedFiles.add("(unknown file): " + e.getCause());
                    listener.onFileProcessed(completed, files.size(), "(unknown file)", true);
                    continue;
                }

                if (taskResult == null) {
                    completed++;
                    continue;
                }
                completed++;

                if (taskResult.failed()) {
                    failedFiles.add(taskResult.relativePath() + ": " + taskResult.failureMessage());
                    listener.onFileProcessed(completed, files.size(), taskResult.relativePath(), true);
                } else if (taskResult.unchanged()) {
                    filesUnchanged++;
                    totalChunks += taskResult.existingChunkCount();
                    listener.onFileProcessed(completed, files.size(), taskResult.relativePath(), false);
                } else if (!taskResult.segments().isEmpty()) {
                    store.removeAll(sourceFilter(taskResult.relativePath()));
                    store.addAll(taskResult.embeddingResponse().content(), taskResult.segments());
                    filesEmbedded++;
                    totalChunks += taskResult.segments().size();
                    if (taskResult.embeddingResponse().tokenUsage() != null) {
                        inputTokensUsed += taskResult.embeddingResponse().tokenUsage().inputTokenCount();
                    }
                    listener.onFileProcessed(completed, files.size(), taskResult.relativePath(), false);
                } else {
                    listener.onFileProcessed(completed, files.size(), taskResult.relativePath(), false);
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
        cachedStore = new CachedStore(store, Files.getLastModifiedTime(indexFile).toMillis());

        long duration = System.currentTimeMillis() - startTime;
        return new IndexReport(files.size(), filesEmbedded, filesUnchanged, removedSources.size(), failedFiles.size(),
                List.copyOf(failedFiles), totalChunks, inputTokensUsed, config.estimatedCostUsd(inputTokensUsed), duration);
    }

    public static final double DEFAULT_MIN_SCORE = 0.0;

    /** Semantically searches the persisted index; throws if {@link #rebuild()} has never been run. */
    public List<SearchHit> search(String query, int maxResults) {
        return search(query, maxResults, DEFAULT_MIN_SCORE);
    }

    /** Semantically searches the persisted index with an explicit minimum similarity score threshold. */
    public List<SearchHit> search(String query, int maxResults, double minScore) {
        InMemoryEmbeddingStore<TextSegment> store = loadStoreCached();
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
        long now = System.currentTimeMillis();
        if (now - lastStaleCheckMillis < STALE_CHECK_MIN_INTERVAL_MS) {
            return lastStaleResult;
        }
        boolean stale = computeStale();
        lastStaleCheckMillis = now;
        lastStaleResult = stale;
        return stale;
    }

    private boolean computeStale() {
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


    /** Loads the persisted store, reusing the in-memory cache when {@link #indexFile}'s last-modified
     * time hasn't changed since it was last read or written by this instance. */
    private InMemoryEmbeddingStore<TextSegment> loadStoreCached() {
        if (!Files.isRegularFile(indexFile)) {
            throw new IllegalStateException(
                    "No semantic index found at " + indexFile + ". Run 'llm-harness index' first.");
        }
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(indexFile).toMillis();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read semantic index at " + indexFile, e);
        }
        CachedStore cached = cachedStore;
        if (cached != null && cached.mtimeMillis() == mtime) {
            return cached.store();
        }
        InMemoryEmbeddingStore<TextSegment> loaded = InMemoryEmbeddingStore.fromFile(indexFile);
        cachedStore = new CachedStore(loaded, mtime);
        return loaded;
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

    private Response<List<Embedding>> embedSegmentsInBatches(List<TextSegment> segments) {
        if (segments.isEmpty()) {
            return Response.from(List.of());
        }

        List<Embedding> allEmbeddings = new ArrayList<>(segments.size());
        int totalInputTokens = 0;
        boolean hasTokenUsage = false;
        int batchSize = Math.max(1, config.maxBatchSize());

        for (int i = 0; i < segments.size(); i += batchSize) {
            List<TextSegment> batch = segments.subList(i, Math.min(i + batchSize, segments.size()));
            Response<List<Embedding>> batchResponse = embedSegmentsWithResilience(batch);
            allEmbeddings.addAll(batchResponse.content());
            if (batchResponse.tokenUsage() != null && batchResponse.tokenUsage().inputTokenCount() != null) {
                totalInputTokens += batchResponse.tokenUsage().inputTokenCount();
                hasTokenUsage = true;
            }
        }

        TokenUsage tokenUsage = hasTokenUsage ? new TokenUsage(totalInputTokens, 0) : null;
        return Response.from(allEmbeddings, tokenUsage);
    }

    private Response<List<Embedding>> embedSegmentsWithResilience(List<TextSegment> batch) {
        if (batch.isEmpty()) {
            return Response.from(List.of());
        }
        try {
            return embeddingModel.embedAll(batch);
        } catch (RuntimeException e) {
            if (batch.size() == 1) {
                throw e;
            }
            int mid = batch.size() / 2;
            List<TextSegment> left = batch.subList(0, mid);
            List<TextSegment> right = batch.subList(mid, batch.size());

            Response<List<Embedding>> leftResp = embedSegmentsWithResilience(left);
            Response<List<Embedding>> rightResp = embedSegmentsWithResilience(right);

            List<Embedding> combined = new ArrayList<>(leftResp.content());
            combined.addAll(rightResp.content());

            int tokens = 0;
            boolean hasTokens = false;
            if (leftResp.tokenUsage() != null && leftResp.tokenUsage().inputTokenCount() != null) {
                tokens += leftResp.tokenUsage().inputTokenCount();
                hasTokens = true;
            }
            if (rightResp.tokenUsage() != null && rightResp.tokenUsage().inputTokenCount() != null) {
                tokens += rightResp.tokenUsage().inputTokenCount();
                hasTokens = true;
            }
            return Response.from(combined, hasTokens ? new TokenUsage(tokens, 0) : null);
        }
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

