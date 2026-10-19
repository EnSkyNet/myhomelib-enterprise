package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import com.github.benmanes.caffeine.cache.Cache;

import static org.assertj.core.api.Assertions.assertThat;

class SearchCacheMemoryBoundTest {
    @Test
    void skipsCachingUnboundedResultsButKeepsSmallSearchResults() {
        CaffeineSearchCache cache = new CaffeineSearchCache();
        BookId id = BookId.fromLong(1L);
        cache.put("huge", Collections.nCopies(100_000, id));
        assertThat(cache.get("huge")).isNull();

        List<BookId> few = List.of(id);
        cache.put("small", few);
        assertThat(cache.get("small")).containsExactly(id);
    }

    @Test
    void boundedCacheEvictsEarlierResultSetsUnderPressure() throws Exception {
        CaffeineSearchCache cache = new CaffeineSearchCache();
        BookId id = BookId.fromLong(2L);
        for (int i = 0; i < 25; i++) {
            cache.put("query-" + i, Collections.nCopies(2_000, id));
        }
        // Force completion of Caffeine's asynchronous maintenance before
        // checking its memory-weight contract. Window TinyLFU may admit or
        // reject any particular query, including the most recent one.
        java.lang.reflect.Field cacheField = CaffeineSearchCache.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        Cache<?, ?> internal = (Cache<?, ?>) cacheField.get(cache);
        internal.cleanUp();
        long retainedWeight = internal.policy().eviction().orElseThrow().weightedSize().orElseThrow();
        assertThat(retainedWeight).isBetween(1L, 20_000L);
    }

    @Test
    void replacingCachedResultWithEmptyOrOversizedResultCannotReturnStaleIds() {
        CaffeineSearchCache cache = new CaffeineSearchCache();
        BookId id = BookId.fromLong(3L);
        cache.put("replaced", List.of(id));
        cache.put("replaced", List.of());
        assertThat(cache.get("replaced")).isNull();

        cache.put("replaced", List.of(id));
        cache.put("replaced", Collections.nCopies(5_001, id));
        assertThat(cache.get("replaced")).isNull();

        cache.put("replaced", List.of(id));
        cache.put("replaced", null);
        assertThat(cache.get("replaced")).isNull();
    }

    @Test
    void cachedResultsAreSnapshotsAndCannotBeMutatedByClients() {
        CaffeineSearchCache cache = new CaffeineSearchCache();
        BookId id = BookId.fromLong(4L);
        ArrayList<BookId> mutable = new ArrayList<>();
        mutable.add(id);
        cache.put("snapshot", mutable);
        mutable.clear();
        assertThat(cache.get("snapshot")).containsExactly(id);
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> cache.get("snapshot").clear());
    }
}
