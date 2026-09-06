package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
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

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    private final LibraryOperationCoordinator operationCoordinator;

    private final AtomicBoolean isInitializing = new AtomicBoolean(false);
    private final AtomicLong rebuildGeneration = new AtomicLong();
    private final AtomicReference<RebuildTask> activeRebuild = new AtomicReference<>();

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
        if (operationCoordinator.isHeldByCurrentThread()) {
            rebuildSearchIndexLocked();
            return;
        }
        try (var ignored = operationCoordinator.acquire(LibraryOperationType.INDEX)) {
            rebuildSearchIndexLocked();
        }
    }

    private void rebuildSearchIndexLocked() {
        log.info("🔄 Перебудова індексу...");
        long startTime = System.currentTimeMillis();
        indexRebuilder.rebuildIndex();
        long duration = System.currentTimeMillis() - startTime;
        log.info("✅ Індекс перебудовано за {} мс. Проіндексовано {} документів",
                duration, indexRebuilder.getIndexedDocumentCount());
    }

    /**
     * Queues a collection-bound rebuild. The executor waits until the initiating SWITCH/CREATE
     * lease is released, then owns a detached INDEX lease for the complete rebuild lifecycle.
     */
    private void rebuildIndexAsync(Collection collection) {
        startAsyncRebuild(collection, false);
    }

    /**
     * Manual async rebuild. INDEX is acquired before the Future is returned, so no IMPORT/SYNC/SWITCH
     * can slip into the scheduling gap between the UI request and the executor actually starting work.
     */
    public CompletableFuture<Void> rebuildSearchIndexAsync() {
        return startAsyncRebuild(collectionLifecyclePort.getCurrentCollection(), true);
    }

    /**
     * Invalidates and cooperatively cancels the current background rebuild, waiting until its
     * detached INDEX lease is released. SWITCH calls this before acquiring its own lease.
     */
    public void cancelBackgroundRebuildAndAwait() {
        rebuildGeneration.incrementAndGet();
        RebuildTask task = activeRebuild.get();
        if (task == null) return;
        task.cancelFlag().set(true);
        try {
            task.future().join();
        } catch (CancellationException | java.util.concurrent.CompletionException expected) {
            log.debug("Попередню фонову перебудову індексу завершено/скасовано перед lifecycle operation");
        }
    }

    private CompletableFuture<Void> startAsyncRebuild(Collection expectedCollection, boolean acquireImmediately) {
        RebuildTask previous = activeRebuild.get();
        // Repeated manual requests are idempotent while the same coordinated rebuild is already running.
        // Cancelling the first task before acquiring its still-held INDEX lease would otherwise make the
        // second request fail with a conflict and leave no rebuild in progress at all.
        if (acquireImmediately && previous != null && !previous.future().isDone()) {
            return previous.future();
        }

        long generation = rebuildGeneration.incrementAndGet();
        if (previous != null) previous.cancelFlag().set(true);

        CompletableFuture<Void> future = new CompletableFuture<>();
        RebuildTask task = new RebuildTask(generation, collectionKey(expectedCollection), new AtomicBoolean(false), future);

        // Publish the task before acquiring the detached INDEX lease. A SWITCH observes the
        // coordinator lease to know that INDEX is active, and it must always be able to find the
        // matching task/cancel flag at that point. Publishing after acquireDetached() left a tiny
        // race where SWITCH saw INDEX but cancelBackgroundRebuildAndAwait() still saw no task.
        activeRebuild.set(task);

        LibraryOperationCoordinator.Lease preAcquiredLease = null;
        if (acquireImmediately) {
            try {
                preAcquiredLease = operationCoordinator.acquireDetached(LibraryOperationType.INDEX);
            } catch (RuntimeException leaseFailure) {
                activeRebuild.compareAndSet(task, null);
                future.completeExceptionally(leaseFailure);
                throw leaseFailure;
            }
        }

        log.info("🔄 Заплановано coordinated Lucene rebuild для колекції {} (generation={}, immediateLease={})",
                expectedCollection == null ? "<current>" : expectedCollection.getName(), generation, acquireImmediately);

        LibraryOperationCoordinator.Lease leaseForTask = preAcquiredLease;
        try {
            executorPort.execute(() -> runAsyncRebuild(task, leaseForTask));
        } catch (RuntimeException schedulingFailure) {
            activeRebuild.compareAndSet(task, null);
            if (preAcquiredLease != null) preAcquiredLease.close();
            future.completeExceptionally(schedulingFailure);
        }
        return future;
    }

    private void runAsyncRebuild(RebuildTask task, LibraryOperationCoordinator.Lease preAcquiredLease) {
        LibraryOperationCoordinator.Lease lease = preAcquiredLease;
        RuntimeException failure = null;
        boolean cancelled = false;
        try {
            if (lease == null) {
                lease = operationCoordinator.acquireDetachedAwait(LibraryOperationType.INDEX);
            }
            if (!isRebuildStillValid(task)) {
                cancelled = true;
            } else {
                long startTime = System.currentTimeMillis();
                indexRebuilder.rebuildIndex(task.cancelFlag());
                if (!isRebuildStillValid(task) || task.cancelFlag().get()) {
                    cancelled = true;
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("✅ Coordinated Lucene rebuild завершено за {} мс; документів {}",
                            duration, indexRebuilder.getIndexedDocumentCount());
                }
            }
        } catch (RuntimeException rebuildFailure) {
            if (task.cancelFlag().get() || task.generation() != rebuildGeneration.get()) {
                cancelled = true;
            } else {
                log.error("❌ Помилка coordinated Lucene rebuild", rebuildFailure);
                failure = rebuildFailure;
            }
        } finally {
            // The Future is the public completion barrier for lifecycle callers. Release the
            // detached INDEX lease first, otherwise join() may return while the coordinator still
            // reports INDEX and an immediately following SWITCH can fail spuriously.
            if (lease != null) {
                try {
                    lease.close();
                } catch (RuntimeException releaseFailure) {
                    if (failure == null && !cancelled) failure = releaseFailure;
                    log.error("❌ Не вдалося звільнити coordinated INDEX lease", releaseFailure);
                }
            }
            activeRebuild.compareAndSet(task, null);
            if (failure != null) {
                task.future().completeExceptionally(failure);
            } else if (cancelled) {
                task.future().cancel(false);
            } else {
                task.future().complete(null);
            }
        }
    }

    private boolean isRebuildStillValid(RebuildTask task) {
        if (task.cancelFlag().get() || task.generation() != rebuildGeneration.get()) return false;
        return Objects.equals(task.collectionKey(), collectionKey(collectionLifecyclePort.getCurrentCollection()));
    }

    private static String collectionKey(Collection collection) {
        return collection == null || collection.getId() == null ? "" : collection.getId();
    }

    private record RebuildTask(long generation, String collectionKey, AtomicBoolean cancelFlag,
                               CompletableFuture<Void> future) { }

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

    public int getIndexedDocumentCount() {
        return indexRebuilder.getIndexedDocumentCount();
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