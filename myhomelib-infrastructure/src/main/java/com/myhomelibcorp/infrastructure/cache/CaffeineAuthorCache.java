package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.application.port.out.cache.AuthorCache;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CaffeineAuthorCache implements AuthorCache {

    private final CollectionManager collectionManager;
    private final com.github.benmanes.caffeine.cache.Cache<String, Author> cache;

    public CaffeineAuthorCache() {
        this(null);
    }

    @Autowired
    public CaffeineAuthorCache(CollectionManager collectionManager) {
        this.collectionManager = collectionManager;
        this.cache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .recordStats()
                .build();
        log.info("CaffeineAuthorCache створено");
    }

    @Override
    public Optional<Author> get(AuthorId id) {
        if (id == null) {
            log.debug("Спроба отримати автора з null ID");
            return Optional.empty();
        }
        return Optional.ofNullable(cache.getIfPresent(key(id)));
    }

    @Override
    public void put(AuthorId id, Author author) {
        if (id != null && author != null) {
            cache.put(key(id), author);
        }
    }

    @Override
    public void evict(AuthorId id) {
        if (id != null) {
            cache.invalidate(key(id));
        }
    }

    private String key(AuthorId id) {
        return CollectionScopedCacheKey.of(collectionManager, id.asString());
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }
}