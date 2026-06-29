package com.myhomelibcorp.infrastructure.cache;

import org.springframework.stereotype.Component;

@Component
public class CacheFactory {

    public <K, V> Cache<K, V> createCache(long maxSize, long expireMinutes) {
        return new InMemoryCache<>(maxSize, expireMinutes);
    }
}