package com.myhomelibcorp.application.port.out.cover;

/**
 * Кеш для зберігання обкладинок у вигляді масивів байтів.
 * Не залежить від JavaFX.
 */
public interface CoverCache {
    byte[] get(String key);
    void put(String key, byte[] imageData);
    void invalidate(String key);
    void clear();
}