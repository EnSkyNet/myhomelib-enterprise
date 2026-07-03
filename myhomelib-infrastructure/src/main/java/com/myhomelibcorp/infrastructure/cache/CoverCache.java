package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.Cache;
import javafx.scene.image.Image;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CoverCache {

    private final CacheFactory cacheFactory;
    private Cache<String, Image> cache;

    @PostConstruct
    public void init() {
        this.cache = cacheFactory.createCache(10_000, 30);
    }

    public Image get(String key) {
        if (key == null) return null;
        return cache.get(key).orElse(null);
    }

    public void put(String key, Image image) {
        if (key != null && image != null) {
            cache.put(key, image);
        }
    }

    public void invalidate(String key) {
        if (key != null) {
            cache.evict(key);
        }
    }

    public void clear() {
        cache.clear();
    }
}