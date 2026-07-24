package com.myhomelibcorp.application.port.out.cache;

/**
 * Порт для асинхронного оновлення кешів словників після імпорту.
 */
public interface CacheRefreshPort {

    /**
     * Асинхронно оновлює кеші авторів, жанрів, серій.
     */
    void refreshCachesAsync();

    /**
     * Синхронне оновлення кешів (якщо потрібно негайно).
     */
    void refreshCachesSync();
}