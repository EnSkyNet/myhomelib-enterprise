package com.myhomelibcorp.reader.core.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded LRU cache of decoded image bytes. */
public class ImageCache {
    private static final int MAX_ENTRIES = 1000;
    private final long maxSizeBytes;
    private long currentSize = 0;
    private final Map<String, byte[]> cache;

    public ImageCache(long maxSizeBytes) {
        this.maxSizeBytes = Math.max(1024 * 1024, maxSizeBytes);
        this.cache = new LinkedHashMap<>(16, 0.75f, true);
    }

    public synchronized void put(String key, byte[] data) {
        if (key == null || data == null || data.length == 0) return;
        if (data.length > maxSizeBytes) return;

        // Replacing an existing entry must reserve only the net additional bytes.
        byte[] existing = cache.remove(key);
        if (existing != null) currentSize -= existing.length;
        ensureCapacity(data.length);
        cache.put(key, data);
        currentSize += data.length;
    }

    public synchronized byte[] get(String key) {
        if (key == null) return null;
        return cache.get(key);
    }

    public synchronized void evict(String key) {
        if (key == null) return;
        byte[] removed = cache.remove(key);
        if (removed != null) currentSize -= removed.length;
    }

    public synchronized void clear() {
        cache.clear();
        currentSize = 0;
    }

    public synchronized long getCurrentSize() { return currentSize; }
    public long getMaxSize() { return maxSizeBytes; }
    public synchronized int size() { return cache.size(); }
    public synchronized boolean contains(String key) { return cache.containsKey(key); }

    private void ensureCapacity(long requiredSize) {
        // Subtraction avoids overflow; enforce the count bound before adding a new entry.
        while (!cache.isEmpty() && (currentSize > maxSizeBytes - requiredSize
                || cache.size() >= MAX_ENTRIES)) {
            var oldest = cache.entrySet().iterator();
            var entry = oldest.next();
            currentSize -= entry.getValue().length;
            oldest.remove();
        }
    }

    public synchronized String getStats() {
        return String.format(java.util.Locale.ROOT, "Images: %d, Size: %.2f MB / %.2f MB",
                cache.size(), currentSize / 1024.0 / 1024.0, maxSizeBytes / 1024.0 / 1024.0);
    }
}
