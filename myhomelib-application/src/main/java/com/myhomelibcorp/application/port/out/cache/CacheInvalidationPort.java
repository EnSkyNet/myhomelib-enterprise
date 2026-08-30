package com.myhomelibcorp.application.port.out.cache;

/**
 * Порт для інвалідації кешів.
 * Використовується при переключенні колекцій.
 */
public interface CacheInvalidationPort {

    /**
     * Очищує всі кеші.
     */
    void invalidateAll();

    /**
     * Очищує кеш книг.
     */
    void invalidateBookCache();

    /**
     * Очищує кеш словників (автори, жанри, серії, групи).
     */

    /**
     * Очищує кеш пошуку.
     */
    void invalidateSearchCache();

    /**
     * Очищує кеш обкладинок.
     */
    void invalidateCoverCache();
}