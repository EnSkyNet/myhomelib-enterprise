package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.application.port.out.cache.AuthorCache;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CaffeineAuthorCache implements AuthorCache {

    private final com.github.benmanes.caffeine.cache.Cache<AuthorId, Author> cache;

    public CaffeineAuthorCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .recordStats()
                .build();
        log.info("CaffeineAuthorCache створено");
    }

    @Override
    public Optional<Author> get(AuthorId id) {
        return Optional.ofNullable(cache.getIfPresent(id));
    }

    @Override
    public void put(AuthorId id, Author author) {
        if (id != null && author != null) {
            cache.put(id, author);
        }
    }

    @Override
    public void evict(AuthorId id) {
        cache.invalidate(id);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }
}