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
}
