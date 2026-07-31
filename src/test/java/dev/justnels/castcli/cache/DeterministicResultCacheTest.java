package dev.justnels.castcli.cache;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicResultCacheTest {

    @Test
    void cachesAndRetrievesReadOnlyResults() {
        DeterministicResultCache cache = new DeterministicResultCache(10);
        String toolName = "summarize_files";
        String input = "{\"files\":[\"FileA.java\"]}";
        String output = "Summary of FileA";

        assertThat(cache.get(toolName, input)).isEmpty();

        cache.put(toolName, input, output);

        Optional<String> hit = cache.get(toolName, input);
        assertThat(hit).isPresent();
        assertThat(hit.get()).isEqualTo(output);
    }

    @Test
    void evictsEldestEntryWhenLimitExceeded() {
        DeterministicResultCache cache = new DeterministicResultCache(2);
        cache.put("t1", "i1", "o1");
        cache.put("t2", "i2", "o2");
        cache.put("t3", "i3", "o3");

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("t1", "i1")).isEmpty();
        assertThat(cache.get("t3", "i3")).isPresent();
    }

    @Test
    void evictsByUtf8ByteBudget() {
        DeterministicResultCache cache = new DeterministicResultCache(10, 70);
        cache.put("t1", "i1", "one");
        cache.put("t2", "i2", "two");

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("t1", "i1")).isEmpty();
        assertThat(cache.stats().evictions()).isEqualTo(1);
        assertThat(cache.stats().bytes()).isLessThanOrEqualTo(70);
    }

    @Test
    void reportsCacheEfficiencyMetricsAndSchemaVersion() {
        DeterministicResultCache cache = new DeterministicResultCache(10);
        cache.get("tool", "missing");
        cache.put("tool", "input", "value");
        cache.get("tool", "input");
        cache.put(null, "input", "ignored");

        DeterministicResultCache.Stats stats = cache.stats();
        assertThat(stats.hits()).isEqualTo(1);
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.puts()).isEqualTo(1);
        assertThat(stats.bypasses()).isEqualTo(1);
        assertThat(stats.keySchemaVersion()).isEqualTo("v2");
    }
}
