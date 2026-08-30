package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.application.port.out.cache.SearchCache;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CaffeineSearchCache implements SearchCache {

    private final com.github.benmanes.caffeine.cache.Cache<String, List<BookId>> cache;

    public CaffeineSearchCache() {
        this.cache = Caffeine.newBuilder()
                // Bound by cached result cardinality, not query count: otherwise
                // 1000 queries x 1000 BookIds can retain ~1M IDs in heap.
                .maximumWeight(50_000)
                .weigher((String key, List<BookId> ids) -> Math.max(1, Math.min(10_000, ids.size())))
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats()
                .build();
        log.info("CaffeineSearchCache створено");
    }

    @Override
    public List<BookId> get(String query) {
        return cache.getIfPresent(query);
    }

    @Override
    public void put(String query, List<BookId> ids) {
        if (query != null && ids != null && !ids.isEmpty()) {
            cache.put(query, ids);
        }
    }

    @Override
    public void evict(String query) {
        cache.invalidate(query);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }
}