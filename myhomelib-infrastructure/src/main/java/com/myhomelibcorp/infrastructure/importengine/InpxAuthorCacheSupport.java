package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.domain.model.author.AuthorNameKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Author-cache sizing, preload and compact seen-name policy used by the INPX import pipeline.
 * This class deliberately contains no import transaction or book persistence logic.
 */
@Slf4j
final class InpxAuthorCacheSupport {
    private static final long PRELOAD_MIN_RECORDS = 100_000L;
    private static final long DELTA_PRELOAD_MIN_RECORDS = 10_000L;
    private static final int SEEN_FILTER_BITS = 1 << 24; // 2 MiB Bloom filter
    private static final int SEEN_FILTER_MASK = SEEN_FILTER_BITS - 1;

    private InpxAuthorCacheSupport() {
    }

    static BitSet newSeenFilter() {
        return new BitSet(SEEN_FILTER_BITS);
    }

    static boolean shouldPreload(boolean onlineCollection, boolean catalogFullSnapshot, long totalRecords) {
        if (!onlineCollection) return false;
        return catalogFullSnapshot
                ? totalRecords >= PRELOAD_MIN_RECORDS
                : totalRecords >= DELTA_PRELOAD_MIN_RECORDS;
    }

    /**
     * Bloom-filter membership with no false negatives for names added through this method.
     * A false positive is intentionally safe: it merely selects the exact DB-resolution path.
     */
    static boolean mightHaveSeen(BitSet filter, AuthorNameKey key) {
        int h1 = mixHash(key.hashCode());
        int h2 = mixHash(31 * key.firstName().hashCode()
                + 17 * key.middleName().hashCode() + key.lastName().hashCode());
        if (h2 == 0) h2 = 0x9E3779B9;
        int i1 = h1 & SEEN_FILTER_MASK;
        int i2 = (h1 + h2) & SEEN_FILTER_MASK;
        int i3 = (h1 + 2 * h2) & SEEN_FILTER_MASK;
        boolean maybeSeen = filter.get(i1) && filter.get(i2) && filter.get(i3);
        filter.set(i1);
        filter.set(i2);
        filter.set(i3);
        return maybeSeen;
    }

    static Map<AuthorNameKey, String> newBoundedCache(int configuredLimit) {
        int limit = normalizedLimit(configuredLimit);
        int initialCapacity = Math.min(65_536, Math.max(16_384, limit / 4));
        return new BoundedAuthorCache(limit, initialCapacity);
    }

    static void preloadIfFits(
            JdbcTemplate jdbc,
            Map<AuthorNameKey, String> authorCache,
            int configuredLimit,
            boolean authorTableInitiallyEmpty) {
        if (authorTableInitiallyEmpty || authorCache == null || !authorCache.isEmpty()) return;
        int limit = normalizedLimit(configuredLimit);
        try {
            Long authorCount = jdbc.queryForObject("SELECT COUNT(*) FROM authors", Long.class);
            if (authorCount == null || authorCount <= 0 || authorCount > limit) {
                if (authorCount != null && authorCount > limit) {
                    log.info("Author preload skipped: {} rows exceed cache limit {}", authorCount, limit);
                }
                return;
            }
            long started = System.nanoTime();
            jdbc.query(
                    "SELECT id, first_name, middle_name, last_name FROM authors",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        String id = rs.getString("id");
                        if (id == null || id.isBlank()) return;
                        authorCache.put(new AuthorNameKey(
                                rs.getString("first_name"),
                                rs.getString("middle_name"),
                                rs.getString("last_name")), id);
                    });
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            log.info("Preloaded {} persistent authors into import cache in {} ms", authorCache.size(), elapsedMs);
        } catch (RuntimeException preloadFailure) {
            // Preload is an optimization only. Preserve the exact DB lookup fallback on any failure.
            authorCache.clear();
            log.warn("Author cache preload failed; continuing with incremental DB resolution", preloadFailure);
        }
    }

    private static int normalizedLimit(int configuredLimit) {
        return Math.max(10_000, Math.min(configuredLimit, 500_000));
    }

    private static int mixHash(int value) {
        int x = value;
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }

    private static final class BoundedAuthorCache extends LinkedHashMap<AuthorNameKey, String> {
        private final int limit;

        private BoundedAuthorCache(int limit, int initialCapacity) {
            super(initialCapacity, 0.75f, true);
            this.limit = limit;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<AuthorNameKey, String> eldest) {
            return size() > limit;
        }
    }
}
