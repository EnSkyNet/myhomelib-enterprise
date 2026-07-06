package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.application.port.out.cache.GenreCache;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CaffeineGenreCache implements GenreCache {

    private final com.github.benmanes.caffeine.cache.Cache<GenreId, Genre> cache;

    public CaffeineGenreCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .recordStats()
                .build();
        log.info("CaffeineGenreCache створено");
    }

    @Override
    public Optional<Genre> get(GenreId id) {
        return Optional.ofNullable(cache.getIfPresent(id));
    }

    @Override
    public void put(GenreId id, Genre genre) {
        if (id != null && genre != null) {
            cache.put(id, genre);
        }
    }

    @Override
    public void evict(GenreId id) {
        cache.invalidate(id);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }
}