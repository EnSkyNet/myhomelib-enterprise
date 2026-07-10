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
                .maximumSize(1000)
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