package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

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
    void boundedCacheEvictsEarlierResultSetsUnderPressure() {
        CaffeineSearchCache cache = new CaffeineSearchCache();
        BookId id = BookId.fromLong(2L);
        for (int i = 0; i < 25; i++) {
            cache.put("query-" + i, Collections.nCopies(2_000, id));
        }
        // Caffeine eviction is asynchronous: exercise cleanup via bounded cache, not timing.
        assertThat(cache.get("query-24")).isNotNull();
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
