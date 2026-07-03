package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.Cache;
import com.myhomelibcorp.application.port.out.cache.GenreCache;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CaffeineGenreCache implements GenreCache {

    private final CacheFactory cacheFactory;
    private Cache<GenreId, Genre> cache;

    @PostConstruct
    public void init() {
        this.cache = cacheFactory.createCache(2_000, 60);
        log.info("GenreCache ініціалізовано (maxSize=2000, expire=60min)");
    }

    @Override
    public Optional<Genre> get(GenreId id) {

        if (id == null) return Optional.empty();
        return cache.get(id);
    }

    @Override
    public void put(GenreId id, Genre genre) {
        if (id != null && genre != null) {
            cache.put(id, genre);
        }
    }

    @Override
    public void evict(GenreId id) {
        if (id != null) {
            cache.evict(id);
        }
    }

    @Override
    public void clear() {
        cache.clear();
    }
}