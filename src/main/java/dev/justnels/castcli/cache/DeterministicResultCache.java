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

    private final int maxEntries;
    private final Map<String, String> cache;

    public DeterministicResultCache() {
        this(100);
    }

    public DeterministicResultCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.cache = new LinkedHashMap<>(maxEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > DeterministicResultCache.this.maxEntries;
            }
        };
    }

    /** Looks up cached result for a tool call. */
    public Optional<String> get(String toolName, String rawInput) {
        if (toolName == null || rawInput == null) return Optional.empty();
        String key = buildCacheKey(toolName, rawInput);
        synchronized (cache) {
            return Optional.ofNullable(cache.get(key));
        }
    }

    /** Stores a result in the cache. */
    public void put(String toolName, String rawInput, String result) {
        if (toolName == null || rawInput == null || result == null || result.isBlank()) return;
        String key = buildCacheKey(toolName, rawInput);
        synchronized (cache) {
            cache.put(key, result);
        }
    }

    /** Clears all cached results. */
    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }

    public int size() {
        synchronized (cache) {
            return cache.size();
        }
    }

    private static String buildCacheKey(String toolName, String rawInput) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest((toolName + ":" + rawInput).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return toolName + ":" + rawInput.hashCode();
        }
    }
}
