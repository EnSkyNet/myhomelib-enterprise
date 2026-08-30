package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.port.out.cache.CacheInvalidationPort;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.port.out.search.SearchIndexLifecycle;
import com.myhomelibcorp.domain.event.collection.CollectionOpenedEvent;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.event.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor
@Slf4j
public class CollectionLifecycleService {

    private final CollectionLifecyclePort collectionLifecyclePort;
    private final DatabaseMigrationPort databaseMigrationPort;
    private final CacheInvalidationPort cacheInvalidationPort;
    private final IndexRebuilder indexRebuilder;
    private final SearchIndexLifecycle searchIndexLifecycle;
    private final DomainEventPublisher eventPublisher;
    private final ExecutorPort executorPort;

    private final AtomicBoolean isInitializing = new AtomicBoolean(false);

    /**
     * Повна ініціалізація колекції: переключення, міграція, кеші, індекс (асинхронно).
     */
    public boolean initializeCollection(Collection collection, boolean rebuildIndex) {
        if (!isInitializing.compareAndSet(false, true)) {
            log.warn("Ініціалізація колекції вже виконується");
            throw new IllegalStateException("Інше переключення колекції вже виконується");
        }

        Collection previous = collectionLifecyclePort.getCurrentCollection();
        boolean changedCollection = previous == null || previous.getId() == null
                || collection.getId() == null || !previous.getId().equals(collection.getId());
        try {
            log.info("🚀 Початок ініціалізації колекції: {}", collection.getName());

            // Each collection owns its Lucene directory. Close the previous index BEFORE
            // switching the DataSource, then activate/validate the target index after migrations.
            if (changedCollection) searchIndexLifecycle.closeCurrentIndex();

            // 1. Переключаємо колекцію; switch closes/checkpoints the previous SQLite datasource.
            collectionLifecyclePort.switchToCollection(collection);
            if (changedCollection && previous != null) searchIndexLifecycle.sealClosedIndex(previous);

            // 2. Виконуємо міграції
            int migrations = databaseMigrationPort.migrateCurrentCollection();
            if (migrations > 0) {
                log.info("✅ Виконано {} міграцій", migrations);
            }

            // 3. Очищуємо кеші
            cacheInvalidationPort.invalidateAll();

            // 4. Validate Lucene freshness before derived series normalization.
            // syncSeriesFromBooks() may write the SQLite file but does not change searchable book data,
            // so it must not by itself force a 500k–1M full index rebuild on startup.
            boolean reusableIndex = searchIndexLifecycle.activateCollectionIndex(collection);

            // 5. Do not run catalog-wide repair/series normalization on the startup critical path.
            // Remote-root repair is handled lazily when a remote book is downloaded; series identities
            // are synchronized after imports. Both operations can scan/write hundreds of thousands of rows
            // and previously kept the splash screen blocked even when Lucene was already reusable.
            boolean shouldRebuild = rebuildIndex && !reusableIndex;
            if (shouldRebuild) rebuildIndexAsync(collection);

            // 6. Публікуємо доменну подію
            eventPublisher.publish(new CollectionOpenedEvent(collection));

            if (shouldRebuild) {
                log.info("✅ Ініціалізацію колекції {} завершено; dirty/absent індекс перебудовується у фоні", collection.getName());
            } else if (reusableIndex) {
                log.info("✅ Ініціалізацію колекції {} завершено; готовий per-collection індекс перевикористано", collection.getName());
            } else {
                log.info("✅ Ініціалізацію колекції {} завершено без автоматичної перебудови індексу", collection.getName());
            }
            return reusableIndex;

        } catch (Exception e) {
            log.error("❌ Помилка ініціалізації колекції: {}", e.getMessage(), e);
            if (changedCollection) {
                restorePreviousCollection(previous);
            }
            throw new RuntimeException("Не вдалося ініціалізувати колекцію: " + e.getMessage(), e);
        } finally {
            isInitializing.set(false);
        }
    }


    /**
     * Перебудова індексу (синхронно). Використовується зовнішніми use cases.
     */
    public void rebuildSearchIndex() {
        log.info("🔄 Перебудова індексу...");
        long startTime = System.currentTimeMillis();
        indexRebuilder.rebuildIndex();
        long duration = System.currentTimeMillis() - startTime;
        log.info("✅ Індекс перебудовано за {} мс. Проіндексовано {} документів",
                duration, indexRebuilder.getIndexedDocumentCount());
    }

    /**
     * Асинхронна перебудова індексу зі статусом у логах.
     */
    private void rebuildIndexAsync(Collection collection) {
        log.info("🔄 Запуск фонової перебудови індексу для колекції: {}", collection.getName());

        executorPort.execute(() -> {
            try {
                long startTime = System.currentTimeMillis();
                log.info("🔄 Початок перебудови індексу (фоново)...");

                indexRebuilder.rebuildIndex();

                long duration = System.currentTimeMillis() - startTime;
                int count = indexRebuilder.getIndexedDocumentCount();
                log.info("✅ Індекс перебудовано за {} мс. Проіндексовано {} документів",
                        duration, count);

            } catch (Exception e) {
                log.error("❌ Помилка фонової перебудови індексу для колекції {}",
                        collection.getName(), e);
            }
        });
    }

    /**
     * Асинхронна перебудова індексу з CompletableFuture.
     */
    public CompletableFuture<Void> rebuildSearchIndexAsync() {
        log.info("🔄 Запуск асинхронної перебудови індексу...");

        CompletableFuture<Void> future = new CompletableFuture<>();
        executorPort.execute(() -> {
            try {
                long startTime = System.currentTimeMillis();
                indexRebuilder.rebuildIndex();
                long duration = System.currentTimeMillis() - startTime;
                log.info("✅ Індекс перебудовано за {} мс. Проіндексовано {} документів",
                        duration, indexRebuilder.getIndexedDocumentCount());
                future.complete(null);
            } catch (Exception e) {
                log.error("❌ Помилка асинхронної перебудови індексу", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Best-effort rollback after a failed migration/cache/index initialization.
     */
    private void restorePreviousCollection(Collection previous) {
        try {
            searchIndexLifecycle.closeCurrentIndex();
            if (previous == null) {
                collectionLifecyclePort.closeCurrentCollection();
                cacheInvalidationPort.invalidateAll();
                log.warn("Невдалу першу колекцію закрито після помилки ініціалізації");
                return;
            }
            Collection failed = collectionLifecyclePort.getCurrentCollection();
            collectionLifecyclePort.switchToCollection(previous);
            if (failed != null) searchIndexLifecycle.sealClosedIndex(failed);
            databaseMigrationPort.migrateCurrentCollection();
            cacheInvalidationPort.invalidateAll();
            if (!searchIndexLifecycle.activateCollectionIndex(previous)) indexRebuilder.rebuildIndex();
            log.warn("Після помилки відновлено попередню колекцію та її пошуковий індекс: {}", previous.getName());
        } catch (Exception rollbackError) {
            log.error("❌ Не вдалося відновити попередню колекцію після помилки ініціалізації", rollbackError);
        }
    }

    /**
     * Закриває поточну колекцію.
     */
    public void closeCollection() {
        Collection current = collectionLifecyclePort.getCurrentCollection();
        searchIndexLifecycle.closeCurrentIndex();
        collectionLifecyclePort.closeCurrentCollection();
        if (current != null) searchIndexLifecycle.sealClosedIndex(current);
        cacheInvalidationPort.invalidateAll();
        log.info("Колекцію закрито");
    }

    /**
     * Отримує поточну колекцію.
     */
    public Collection getCurrentCollection() {
        return collectionLifecyclePort.getCurrentCollection();
    }

    /**
     * Оновлює metadata активної колекції без закриття/відкриття SQLite.
     */
    public void updateCurrentCollection(Collection collection) {
        collectionLifecyclePort.updateCurrentCollection(collection);
    }

    /**
     * Перевіряє, чи колекція готова до роботи.
     */
    public boolean isCollectionReady() {
        return collectionLifecyclePort.isCollectionReady();
    }

    /**
     * Перевіряє, чи виконується ініціалізація.
     */
    public boolean isInitializing() {
        return isInitializing.get();
    }
}