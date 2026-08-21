package com.myhomelibcorp.reader.core.cache;

import java.util.LinkedHashMap;
import java.util.Map;

public class ImageCache {

    private final long maxSizeBytes;
    private long currentSize = 0;
    private final Map<String, byte[]> cache;

    public ImageCache(long maxSizeBytes) {
        this.maxSizeBytes = Math.max(1024 * 1024, maxSizeBytes);
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                if (size() > 1000) {
                    currentSize -= eldest.getValue().length;
                    return true;
                }
                return false;
            }
        };
    }

    public synchronized void put(String key, byte[] data) {
        if (key == null || data == null || data.length == 0) {
            return;
        }

        if (data.length > maxSizeBytes) {
            return;
        }

        ensureCapacity(data.length);

        byte[] existing = cache.put(key, data);
        if (existing != null) {
            currentSize -= existing.length;
        }
        currentSize += data.length;
    }

    public synchronized byte[] get(String key) {
        if (key == null) {
            return null;
        }
        return cache.get(key);
    }

    public synchronized void evict(String key) {
        if (key == null) {
            return;
        }
        byte[] removed = cache.remove(key);
        if (removed != null) {
            currentSize -= removed.length;
        }
    }

    public synchronized void clear() {
        cache.clear();
        currentSize = 0;
    }

    public long getCurrentSize() {
        return currentSize;
    }

    public long getMaxSize() {
        return maxSizeBytes;
    }

    public int size() {
        return cache.size();
    }

    public boolean contains(String key) {
        return cache.containsKey(key);
    }

    private void ensureCapacity(long requiredSize) {
        while (currentSize + requiredSize > maxSizeBytes && !cache.isEmpty()) {
            String oldestKey = cache.keySet().iterator().next();
            byte[] removed = cache.remove(oldestKey);
            if (removed != null) {
                currentSize -= removed.length;
            }
        }
    }

    public String getStats() {
        return String.format("Images: %d, Size: %.2f MB / %.2f MB",
                cache.size(),
                currentSize / 1024.0 / 1024.0,
                maxSizeBytes / 1024.0 / 1024.0);
    }
}