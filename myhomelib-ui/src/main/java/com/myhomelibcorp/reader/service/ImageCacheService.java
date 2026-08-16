package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.reader.model.ImageData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@Slf4j
public class ImageCacheService {

    // Кеш зображень з обмеженням за розміром
    private final ConcurrentMap<String, byte[]> imageCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> imageMimeTypes = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Long> accessOrder = new LinkedHashMap<>(16, 0.75f, true);

    private static final long MAX_CACHE_SIZE_BYTES = 50 * 1024 * 1024; // 50 MB
    private static final int MAX_CACHE_ITEMS = 100;
    private long currentCacheSize = 0;

    /**
     * Додає зображення до кешу.
     */
    public void put(String id, byte[] data, String mimeType) {
        if (id == null || data == null || data.length == 0) {
            return;
        }

        // Перевіряємо чи вистачає місця
        long dataSize = data.length;
        ensureCapacity(dataSize);

        // Додаємо до кешу
        imageCache.put(id, data);
        if (mimeType != null) {
            imageMimeTypes.put(id, mimeType);
        }
        accessOrder.put(id, System.currentTimeMillis());
        currentCacheSize += dataSize;

        log.debug("Image cached: id={}, size={} KB, total={} MB",
                id, dataSize / 1024, currentCacheSize / 1024 / 1024);
    }

    /**
     * Отримує зображення з кешу.
     */
    public byte[] get(String id) {
        if (id == null) {
            return null;
        }
        byte[] data = imageCache.get(id);
        if (data != null) {
            accessOrder.put(id, System.currentTimeMillis());
        }
        return data;
    }

    /**
     * Отримує MIME тип зображення.
     */
    public String getMimeType(String id) {
        return imageMimeTypes.get(id);
    }

    /**
     * Перевіряє чи є зображення в кеші.
     */
    public boolean contains(String id) {
        return imageCache.containsKey(id);
    }

    /**
     * Видаляє зображення з кешу.
     */
    public void evict(String id) {
        if (id == null) {
            return;
        }
        byte[] data = imageCache.remove(id);
        if (data != null) {
            currentCacheSize -= data.length;
            accessOrder.remove(id);
            imageMimeTypes.remove(id);
            log.debug("Image evicted: id={}, size={} KB", id, data.length / 1024);
        }
    }

    /**
     * Очищає весь кеш.
     */
    public void clear() {
        imageCache.clear();
        imageMimeTypes.clear();
        accessOrder.clear();
        currentCacheSize = 0;
        log.info("Image cache cleared");
    }

    /**
     * Отримує поточний розмір кешу в байтах.
     */
    public long getCurrentSize() {
        return currentCacheSize;
    }

    /**
     * Отримує кількість зображень у кеші.
     */
    public int getItemCount() {
        return imageCache.size();
    }

    /**
     * Забезпечує достатньо місця для нового зображення.
     */
    private void ensureCapacity(long requiredSize) {
        // Якщо зображення більше за максимальний розмір кешу - не кешуємо
        if (requiredSize > MAX_CACHE_SIZE_BYTES) {
            log.debug("Image too large ({} MB), not caching", requiredSize / 1024 / 1024);
            return;
        }

        // Видаляємо старі зображення поки не буде достатньо місця
        while (currentCacheSize + requiredSize > MAX_CACHE_SIZE_BYTES ||
                imageCache.size() >= MAX_CACHE_ITEMS) {
            if (accessOrder.isEmpty()) {
                break;
            }
            // Знаходимо найстаріший запис
            String oldestId = accessOrder.entrySet().iterator().next().getKey();
            evict(oldestId);
        }
    }

    /**
     * Отримує статистику кешу.
     */
    public String getStats() {
        return String.format("Images: %d, Size: %.2f MB",
                imageCache.size(),
                currentCacheSize / 1024.0 / 1024.0);
    }
}