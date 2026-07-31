package dev.justnels.castcli.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Thread-safe LRU cache for deterministic read-only delegation tools.
 * Avoids redundant LLM invocations when processing identical inputs.
 */
public final class DeterministicResultCache {

    static final String KEY_SCHEMA_VERSION = "v2";
    private final int maxEntries;
    private final long maxBytes;
    private final Map<String, CacheEntry> cache;
    private long currentBytes;
    private long hits;
    private long misses;
    private long puts;
    private long evictions;
    private long bypasses;

    public DeterministicResultCache() {
        this(100, 8L * 1024 * 1024);
    }

    public DeterministicResultCache(int maxEntries) {
        this(maxEntries, 8L * 1024 * 1024);
    }

    public DeterministicResultCache(int maxEntries, long maxBytes) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxBytes = Math.max(1, maxBytes);
        this.cache = new LinkedHashMap<>(maxEntries, 0.75f, true);
    }

    /** Looks up cached result for a tool call. */
    public Optional<String> get(String toolName, String rawInput) {
        if (toolName == null || rawInput == null) {
            recordBypass();
            return Optional.empty();
        }
        String key = buildCacheKey(toolName, rawInput);
        synchronized (cache) {
            CacheEntry entry = cache.get(key);
            if (entry == null) {
                misses++;
                return Optional.empty();
            }
            hits++;
            return Optional.of(entry.value());
        }
    }

    /** Stores a result in the cache. */
    public void put(String toolName, String rawInput, String result) {
        if (toolName == null || rawInput == null || result == null || result.isBlank()) {
            recordBypass();
            return;
        }
        String key = buildCacheKey(toolName, rawInput);
        long entryBytes = key.getBytes(StandardCharsets.UTF_8).length
                + result.getBytes(StandardCharsets.UTF_8).length;
        synchronized (cache) {
            if (entryBytes > maxBytes) {
                bypasses++;
                return;
            }
            CacheEntry previous = cache.put(key, new CacheEntry(result, entryBytes, System.nanoTime()));
            if (previous != null) currentBytes -= previous.bytes();
            currentBytes += entryBytes;
            puts++;
            evictToBounds();
        }
    }

    /** Clears all cached results. */
    public void clear() {
        synchronized (cache) {
            cache.clear();
            currentBytes = 0;
        }
    }

    public int size() {
        synchronized (cache) {
            return cache.size();
        }
    }

    private void evictToBounds() {
        var iterator = cache.entrySet().iterator();
        while ((cache.size() > maxEntries || currentBytes > maxBytes) && iterator.hasNext()) {
            CacheEntry removed = iterator.next().getValue();
            currentBytes -= removed.bytes();
            iterator.remove();
            evictions++;
        }
    }

    private void recordBypass() {
        synchronized (cache) {
            bypasses++;
        }
    }

    public Stats stats() {
        synchronized (cache) {
            long oldestCreatedNanos = Long.MAX_VALUE;
            for (CacheEntry entry : cache.values()) {
                oldestCreatedNanos = Math.min(oldestCreatedNanos, entry.createdNanos());
            }
            long oldestAgeMs = oldestCreatedNanos == Long.MAX_VALUE ? 0
                    : Math.max(0, (System.nanoTime() - oldestCreatedNanos) / 1_000_000);
            return new Stats(cache.size(), currentBytes, maxEntries, maxBytes, hits, misses, puts,
                    evictions, bypasses, oldestAgeMs, KEY_SCHEMA_VERSION);
        }
    }

    private static String buildCacheKey(String toolName, String rawInput) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest((KEY_SCHEMA_VERSION + ":" + toolName + ":" + rawInput)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return KEY_SCHEMA_VERSION + ":" + toolName + ":" + rawInput.hashCode();
        }
    }

    private record CacheEntry(String value, long bytes, long createdNanos) { }

    public record Stats(int entries, long bytes, int maxEntries, long maxBytes, long hits,
                        long misses, long puts, long evictions, long bypasses,
                        long oldestEntryAgeMs, String keySchemaVersion) { }
}
