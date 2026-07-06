package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.application.port.out.cache.SeriesCache;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CaffeineSeriesCache implements SeriesCache {

    private final com.github.benmanes.caffeine.cache.Cache<SeriesId, Series> cache;

    public CaffeineSeriesCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .recordStats()
                .build();
        log.info("CaffeineSeriesCache створено");
    }

    @Override
    public Optional<Series> get(SeriesId id) {
        return Optional.ofNullable(cache.getIfPresent(id));
    }

    @Override
    public void put(SeriesId id, Series series) {
        if (id != null && series != null) {
            cache.put(id, series);
        }
    }

    @Override
    public void evict(SeriesId id) {
        cache.invalidate(id);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }
}