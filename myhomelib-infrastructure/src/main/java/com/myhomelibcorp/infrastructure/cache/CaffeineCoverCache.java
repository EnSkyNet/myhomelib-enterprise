package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.application.port.out.CoverCache;
import javafx.scene.image.Image;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class CaffeineCoverCache implements CoverCache {

    private final Cache<String, Image> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();

    @Override
    public Image get(String key) {
        return cache.getIfPresent(key);
    }

    @Override
    public void put(String key, Image image) {
        cache.put(key, image);
    }

    @Override
    public void invalidate(String key) {
        cache.invalidate(key);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }
}