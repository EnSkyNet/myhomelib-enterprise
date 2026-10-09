package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.GenreCache;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class CaffeineGenreCache implements GenreCache {
    private final CollectionScopedCaffeineCache<GenreId, Genre> cache;

    public CaffeineGenreCache() {
        this(null);
    }

    @Autowired
    public CaffeineGenreCache(CollectionManager collectionManager) {
        this.cache = new CollectionScopedCaffeineCache<>(collectionManager, 5_000,
                Duration.ofMinutes(60), GenreId::asString);
    }

    @Override public Optional<Genre> get(GenreId id) { return cache.get(id); }
    @Override public void put(GenreId id, Genre genre) { cache.put(id, genre); }
    @Override public void evict(GenreId id) { cache.evict(id); }
    @Override public void clear() { cache.clear(); }
}
