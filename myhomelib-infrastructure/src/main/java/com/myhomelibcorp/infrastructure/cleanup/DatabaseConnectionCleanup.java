package com.myhomelibcorp.infrastructure.cleanup;

import com.myhomelibcorp.infrastructure.cache.BookCache;
import com.myhomelibcorp.infrastructure.cache.DictionaryCache;
import com.myhomelibcorp.infrastructure.cache.CaffeineSearchCache;
import com.myhomelibcorp.infrastructure.cache.CaffeineCoverCache;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseConnectionCleanup {

    private final CollectionManager collectionManager;
    private final BookCache bookCache;
    private final DictionaryCache dictionaryCache;
    private final CaffeineSearchCache searchCache;
    private final CaffeineCoverCache coverCache;

    public void cleanupAll() {
        log.info("🧹 Початок повного очищення ресурсів...");

        // 1. Очищення кешів
        bookCache.clear();
        dictionaryCache.clearAll();
        searchCache.clear();
        coverCache.clear();
        log.info("  ✅ Кеші очищено");

        // 2. Закриття активних з'єднань
        DataSource ds = collectionManager.getCurrentDataSource();
        if (ds != null) {
            try {
                if (ds instanceof com.zaxxer.hikari.HikariDataSource hikariDs) {
                    try {
                        Method evictMethod = hikariDs.getClass().getMethod("evictConnections");
                        evictMethod.invoke(hikariDs);
                        log.info("  ✅ Evict connections виконано");
                    } catch (NoSuchMethodException e) {
                        log.info("  ℹ️ evictConnections не підтримується, закриваємо DataSource");
                        hikariDs.close();
                        log.info("  ✅ HikariDataSource закрито");
                    } catch (Exception e) {
                        log.warn("  ⚠️ Не вдалося evict connections: {}", e.getMessage());
                        try {
                            hikariDs.close();
                            log.info("  ✅ HikariDataSource закрито (fallback)");
                        } catch (Exception ex) {
                            log.warn("  ⚠️ Не вдалося закрити DataSource: {}", ex.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("  ⚠️ Не вдалося закрити з'єднання: {}", e.getMessage());
            }
        }

        // 3. Закриття колекції
        collectionManager.forceCloseCurrentCollection();
        log.info("  ✅ Колекцію закрито");

        // 4. Примусове звільнення пам'яті
        System.gc();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("🧹 Очищення ресурсів завершено");
    }

    public boolean isFullyCleaned() {
        DataSource ds = collectionManager.getCurrentDataSource();
        if (ds == null) return true;

        try (Connection conn = ds.getConnection()) {
            return conn.isValid(1);
        } catch (SQLException e) {
            return false;
        }
    }
}