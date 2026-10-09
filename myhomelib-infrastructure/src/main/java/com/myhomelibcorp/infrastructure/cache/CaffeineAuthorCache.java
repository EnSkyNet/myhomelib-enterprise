package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.AuthorCache;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class CaffeineAuthorCache implements AuthorCache {
    private final CollectionScopedCaffeineCache<AuthorId, Author> cache;

    public CaffeineAuthorCache() {
        this(null);
    }

    @Autowired
    public CaffeineAuthorCache(CollectionManager collectionManager) {
        this.cache = new CollectionScopedCaffeineCache<>(collectionManager, 10_000,
                Duration.ofMinutes(30), AuthorId::asString);
    }

    @Override public Optional<Author> get(AuthorId id) { return cache.get(id); }
    @Override public void put(AuthorId id, Author author) { cache.put(id, author); }
    @Override public void evict(AuthorId id) { cache.evict(id); }
    @Override public void clear() { cache.clear(); }
}
