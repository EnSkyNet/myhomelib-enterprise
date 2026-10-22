package com.myhomelibcorp.reader.core.cache;

import com.myhomelibcorp.reader.api.PageDimensions;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.layout.TextLayoutEngine;
import com.myhomelibcorp.reader.model.PageLayout;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small LRU cache for the nearest reader pages. */
public class PageCache {
    private final int maxSize;
    private final Map<PageKey, PageLayout> cache;

    public PageCache(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
        // A large configured limit must not allocate an enormous map on construction.
        this.cache = new LinkedHashMap<>(Math.min(this.maxSize, 16), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<PageKey, PageLayout> eldest) {
                return size() > PageCache.this.maxSize;
            }
        };
    }

    public synchronized PageLayout getOrCompute(
            ReaderDocument document, ReaderPosition position,
            PageDimensions dimensions, TextLayoutEngine layoutEngine) {
        PageKey key = buildKey(document, position, dimensions);
        PageLayout cached = cache.get(key);
        if (cached != null) return cached;
        PageLayout layout = layoutEngine.layoutPage(document, position.textOffset(), dimensions);
        if (layout != null) cache.put(key, layout);
        return layout;
    }

    public synchronized void clear() { cache.clear(); }

    /** Evict all page offsets and dimensions for an explicit document identifier. */
    public synchronized void evict(String documentId) {
        if (documentId == null) return;
        cache.keySet().removeIf(key -> documentId.equals(key.documentKey()));
    }

    public synchronized int size() { return cache.size(); }
    public int getMaxSize() { return maxSize; }

    private PageKey buildKey(ReaderDocument document, ReaderPosition position, PageDimensions d) {
        return new PageKey(documentKey(document), position.textOffset(), d.width(), d.height(),
                d.leftMargin(), d.rightMargin(), d.topMargin(), d.bottomMargin());
    }

    public synchronized void evictForDocument(ReaderDocument document) {
        Object identity = documentKey(document);
        cache.keySet().removeIf(key -> key.documentKey().equals(identity));
    }

    private static Object documentKey(ReaderDocument document) {
        var metadata = document.metadata();
        String id = metadata == null ? null : metadata.id();
        return id == null || id.isBlank() ? new AnonymousDocumentKey(document) : id;
    }

    /** Missing book IDs must not make unrelated documents share cached pages. */
    private static final class AnonymousDocumentKey {
        private final ReaderDocument document;

        AnonymousDocumentKey(ReaderDocument document) { this.document = document; }

        @Override public boolean equals(Object other) {
            return other instanceof AnonymousDocumentKey key && key.document == document;
        }

        @Override public int hashCode() { return System.identityHashCode(document); }
    }

    private record PageKey(Object documentKey, long offset, int width, int height,
                           int left, int right, int top, int bottom) { }
}
