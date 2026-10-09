package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.application.port.out.cache.SearchCache;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CaffeineSearchCache implements SearchCache {

    private final CollectionManager collectionManager;
    private final com.github.benmanes.caffeine.cache.Cache<String, List<BookId>> cache;

    public CaffeineSearchCache() {
        this(null);
    }

    @Autowired
    public CaffeineSearchCache(CollectionManager collectionManager) {
        this.collectionManager = collectionManager;
        this.cache = Caffeine.newBuilder()
                // Bound by cached result cardinality, not query count: otherwise
                // 1000 queries x 1000 BookIds can retain ~1M IDs in heap.
                .maximumWeight(20_000)
                .weigher((String key, List<BookId> ids) -> Math.max(1, ids.size()))
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats()
                .build();
        log.info("CaffeineSearchCache створено");
    }

    @Override
    public List<BookId> get(String query) {
        if (query == null) return null;
        return cache.getIfPresent(key(query));
    }

    @Override
    public void put(String query, List<BookId> ids) {
        // Large result sets are already retained by the caller; caching them a second
        // time gives little benefit and can retain millions of UUID objects.
        if (query == null) return;
        String scopedKey = key(query);
        if (ids == null || ids.isEmpty() || ids.size() > 5_000) {
            // A new empty/oversized result must invalidate a previously cached result:
            // silently ignoring it would keep returning a stale search result.
            cache.invalidate(scopedKey);
            return;
        }
        // The caller may reuse and mutate its result buffer after publication.
        // Keep an immutable snapshot so subsequent searches cannot observe that mutation.
        cache.put(scopedKey, List.copyOf(ids));
    }

    @Override
    public void evict(String query) {
        if (query != null) cache.invalidate(key(query));
    }

    private String key(String query) {
        return CollectionScopedCacheKey.of(collectionManager, query);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }
}