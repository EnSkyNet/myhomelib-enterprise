package com.myhomelibcorp.reader.core.cache;

import com.myhomelibcorp.reader.api.PageDimensions;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.layout.TextLayoutEngine;
import com.myhomelibcorp.reader.model.PageLayout;

import java.util.LinkedHashMap;
import java.util.Map;

public class PageCache {

    private final int maxSize;
    private final Map<String, PageLayout> cache;

    public PageCache(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
        this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PageLayout> eldest) {
                return size() > PageCache.this.maxSize;
            }
        };
    }

    public PageLayout getOrCompute(
            ReaderDocument document,
            ReaderPosition position,
            PageDimensions dimensions,
            TextLayoutEngine layoutEngine
    ) {
        String key = buildKey(document, position, dimensions);
        PageLayout cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        PageLayout layout = layoutEngine.layoutPage(document, position.textOffset(), dimensions);
        if (layout != null) {
            cache.put(key, layout);
        }
        return layout;
    }

    public PageLayout get(String key) {
        return cache.get(key);
    }

    public void put(String key, PageLayout layout) {
        if (layout != null) {
            cache.put(key, layout);
        }
    }

    public void clear() {
        cache.clear();
    }

    public void evict(String key) {
        cache.remove(key);
    }

    public int size() {
        return cache.size();
    }

    public int getMaxSize() {
        return maxSize;
    }

    private String buildKey(ReaderDocument document, ReaderPosition position, PageDimensions dimensions) {
        return document.metadata().id() + ":" +
                position.textOffset() + ":" +
                dimensions.width() + "x" + dimensions.height();
    }

    public void evictForDocument(ReaderDocument document) {
        String prefix = document.metadata().id() + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
    }
}