package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.application.port.out.cover.CoverCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CaffeineCoverCache implements CoverCache {

    private final com.github.benmanes.caffeine.cache.Cache<String, byte[]> cache;

    public CaffeineCoverCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .recordStats()
                .build();
        log.info("CaffeineCoverCache створено (зберігає byte[])");
    }

    @Override
    public byte[] get(String key) {
        return cache.getIfPresent(key);
    }

    @Override
    public void put(String key, byte[] imageData) {
        if (key != null && imageData != null && imageData.length > 0) {
            cache.put(key, imageData);
        }
    }

    @Override
    public void invalidate(String key) {
        if (key != null) {
            cache.invalidate(key);
        }
    }

    @Override
    public void clear() {
        cache.invalidateAll();
        log.debug("Обкладинки видалено з кешу");
    }
}