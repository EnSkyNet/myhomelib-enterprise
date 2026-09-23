package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.application.port.out.cache.SeriesCache;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CaffeineSeriesCache implements SeriesCache {

    private final CollectionManager collectionManager;
    private final com.github.benmanes.caffeine.cache.Cache<String, Series> cache;

    public CaffeineSeriesCache() {
        this(null);
    }

    @Autowired
    public CaffeineSeriesCache(CollectionManager collectionManager) {
        this.collectionManager = collectionManager;
        this.cache = Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .recordStats()
                .build();
        log.info("CaffeineSeriesCache створено");
    }

    @Override
    public Optional<Series> get(SeriesId id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(cache.getIfPresent(key(id)));
    }

    @Override
    public void put(SeriesId id, Series series) {
        if (id != null && series != null) {
            cache.put(key(id), series);
        }
    }

    @Override
    public void evict(SeriesId id) {
        if (id != null) cache.invalidate(key(id));
    }

    private String key(SeriesId id) {
        return CollectionScopedCacheKey.of(collectionManager, id.asString());
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }
}