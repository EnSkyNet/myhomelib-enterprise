package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.application.port.out.cache.GenreCache;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CaffeineGenreCache implements GenreCache {

    private final CollectionManager collectionManager;
    private final com.github.benmanes.caffeine.cache.Cache<String, Genre> cache;

    public CaffeineGenreCache() {
        this(null);
    }

    @Autowired
    public CaffeineGenreCache(CollectionManager collectionManager) {
        this.collectionManager = collectionManager;
        this.cache = Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .recordStats()
                .build();
        log.info("CaffeineGenreCache створено");
    }

    @Override
    public Optional<Genre> get(GenreId id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(cache.getIfPresent(key(id)));
    }

    @Override
    public void put(GenreId id, Genre genre) {
        if (id != null && genre != null) {
            cache.put(key(id), genre);
        }
    }

    @Override
    public void evict(GenreId id) {
        if (id != null) cache.invalidate(key(id));
    }

    private String key(GenreId id) {
        return CollectionScopedCacheKey.of(collectionManager, id.asString());
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }
}