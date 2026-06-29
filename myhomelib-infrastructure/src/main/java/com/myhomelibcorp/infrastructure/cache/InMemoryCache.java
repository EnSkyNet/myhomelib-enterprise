package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public class InMemoryCache<K, V> implements com.myhomelibcorp.infrastructure.cache.Cache<K, V> {

    private final Cache<K, V> cache;

    public InMemoryCache(long maxSize, long expireMinutes) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireMinutes, TimeUnit.MINUTES)
                .recordStats()
                .build();
        log.info("Створено InMemoryCache: maxSize={}, expireMinutes={}", maxSize, expireMinutes);
    }

    @Override
    public Optional<V> get(K key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    @Override
    public void put(K key, V value) {
        if (key != null && value != null) {
            cache.put(key, value);
        }
    }

    @Override
    public void evict(K key) {
        cache.invalidate(key);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }
}