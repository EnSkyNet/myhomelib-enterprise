package com.myhomelibcorp.infrastructure.cleanup;

import com.myhomelibcorp.infrastructure.cache.BookCache;
import com.myhomelibcorp.infrastructure.cache.CaffeineSearchCache;
import com.myhomelibcorp.infrastructure.cache.CaffeineCoverCache;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseConnectionCleanup {

    private final CollectionManager collectionManager;
    private final BookCache bookCache;
    private final CaffeineSearchCache searchCache;
    private final CaffeineCoverCache coverCache;

    public void cleanupAll() {
        log.info("🧹 Початок повного очищення ресурсів...");

        // 1. Очищення кешів
        bookCache.clear();
        searchCache.clear();
        coverCache.clear();
        log.info("  ✅ Кеші очищено");

        // 2. CollectionManager is the single owner of the active Hikari pool.
        // Closing it here once avoids reflection against a non-existent
        // HikariDataSource.evictConnections() method and a second close below.
        collectionManager.forceCloseCurrentCollection();
        log.info("  ✅ Колекцію закрито");

        log.info("🧹 Очищення ресурсів завершено");
    }

}