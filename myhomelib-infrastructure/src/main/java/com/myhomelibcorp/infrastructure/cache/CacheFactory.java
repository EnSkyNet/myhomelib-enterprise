package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.Cache;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CacheFactory {

    public <K, V> Cache<K, V> createCache(long maxSize, long expireMinutes) {
        return new CaffeineCache<>(maxSize, expireMinutes);
    }

    public <K, V> Cache<K, V> createCache(long maxSize, Duration expireDuration) {
        return new CaffeineCache<>(maxSize, expireDuration);
    }
}