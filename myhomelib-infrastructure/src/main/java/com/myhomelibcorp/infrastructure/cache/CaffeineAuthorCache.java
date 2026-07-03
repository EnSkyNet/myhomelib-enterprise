package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.AuthorCache;
import com.myhomelibcorp.application.port.out.cache.Cache;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CaffeineAuthorCache implements AuthorCache {

    private final CacheFactory cacheFactory;
    private Cache<AuthorId, Author> cache;

    @PostConstruct
    public void init() {
        this.cache = cacheFactory.createCache(5_000, 60);
        log.info("AuthorCache ініціалізовано (maxSize=5000, expire=60min)");
    }

    @Override
    public Optional<Author> get(AuthorId id) {
        if (id == null) return Optional.empty();
        return cache.get(id);
    }

    @Override
    public void put(AuthorId id, Author author) {
        if (id != null && author != null) {
            cache.put(id, author);
        }
    }

    @Override
    public void evict(AuthorId id) {
        if (id != null) {
            cache.evict(id);
        }
    }

    @Override
    public void clear() {
        cache.clear();
    }
}