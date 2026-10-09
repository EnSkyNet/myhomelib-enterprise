package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.SeriesCache;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class CaffeineSeriesCache implements SeriesCache {
    private final CollectionScopedCaffeineCache<SeriesId, Series> cache;

    public CaffeineSeriesCache() {
        this(null);
    }

    @Autowired
    public CaffeineSeriesCache(CollectionManager collectionManager) {
        this.cache = new CollectionScopedCaffeineCache<>(collectionManager, 5_000,
                Duration.ofMinutes(60), SeriesId::asString);
    }

    @Override public Optional<Series> get(SeriesId id) { return cache.get(id); }
    @Override public void put(SeriesId id, Series series) { cache.put(id, series); }
    @Override public void evict(SeriesId id) { cache.evict(id); }
    @Override public void clear() { cache.clear(); }
}
