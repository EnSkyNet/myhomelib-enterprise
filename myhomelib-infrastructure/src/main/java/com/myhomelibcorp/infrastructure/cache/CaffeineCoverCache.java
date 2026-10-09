package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.application.port.out.cover.CoverCache;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CaffeineCoverCache implements CoverCache {

    private final CollectionManager collectionManager;
    private final com.github.benmanes.caffeine.cache.Cache<String, byte[]> cache;

    public CaffeineCoverCache() {
        this(null);
    }

    @Autowired
    public CaffeineCoverCache(CollectionManager collectionManager) {
        this.collectionManager = collectionManager;
        this.cache = Caffeine.newBuilder()
                // Covers vary by orders of magnitude in size. Bound the cache by
                // retained bytes instead of entry count so large covers cannot
                // consume hundreds of MiB on million-book catalog browsing.
                .maximumWeight(24 * 1024 * 1024)
                .weigher((String key, byte[] data) -> Math.max(1, data.length))
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .recordStats()
                .build();
        log.info("CaffeineCoverCache створено (зберігає byte[])");
    }

    @Override
    public byte[] get(String key) {
        if (key == null) return null;
        return cache.getIfPresent(scopedKey(key));
    }

    @Override
    public void put(String key, byte[] imageData) {
        if (key != null && imageData != null && imageData.length > 0) {
            // Do not retain pathological single covers. They can still be served
            // to the caller; they simply bypass the in-memory cache.
            if (imageData.length <= 16 * 1024 * 1024) cache.put(scopedKey(key), imageData);
        }
    }

    @Override
    public void invalidate(String key) {
        if (key != null) {
            cache.invalidate(scopedKey(key));
        }
    }

    private String scopedKey(String key) {
        return CollectionScopedCacheKey.of(collectionManager, key);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
        log.debug("Обкладинки видалено з кешу");
    }
}