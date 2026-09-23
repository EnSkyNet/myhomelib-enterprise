package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseToolsService {

    private final CollectionLifecycleService collectionLifecycleService;
    private final CollectionStorageManager collectionStorageManager;
    private final LibraryOperationCoordinator operationCoordinator;

    /**
     * Перебудовує пошуковий індекс.
     */
    public void rebuildIndex() {
        collectionLifecycleService.rebuildSearchIndex();
    }

    /** Manual background rebuild routed through the same collection-bound coordinator as auto rebuilds. */
    public java.util.concurrent.CompletableFuture<Void> rebuildIndexAsync() {
        return collectionLifecycleService.rebuildSearchIndexAsync();
    }

    /**
     * Отримує кількість проіндексованих документів.
     */
    public int getIndexedDocumentCount() {
        return collectionLifecycleService.getIndexedDocumentCount();
    }

    /**
     * Reads the index count only while the collection that initiated the UI operation is still active.
     * QUERY keeps SWITCH/RESTORE/DELETE from changing the active index between identity validation
     * and the actual count read.
     */
    public int getIndexedDocumentCount(String expectedCollectionId) {
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        try {
            if (expectedCollectionId != null && !expectedCollectionId.isBlank()) {
                var current = collectionLifecycleService.getCurrentCollection();
                String actual = current == null ? null : current.getId();
                if (!expectedCollectionId.equals(actual)) {
                    throw new IllegalStateException("Active collection changed before reading index count: expected="
                            + expectedCollectionId + ", actual=" + actual);
                }
            }
            int count = collectionLifecycleService.getIndexedDocumentCount();
            lease.markCompleted("");
            return count;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    /**
     * Виконує VACUUM на базі даних колекції.
     */
    public void vacuumCurrent() {
        vacuumCurrent(null);
    }

    /** Async/UI-safe VACUUM that refuses to drift to another active collection while queued. */
    public void vacuumCurrent(String expectedCollectionId) {
        try (var ignored = operationCoordinator.acquire(LibraryOperationType.VACUUM)) {
            if (expectedCollectionId != null && !expectedCollectionId.isBlank()) {
                var current = collectionLifecycleService.getCurrentCollection();
                String actual = current == null ? null : current.getId();
                if (!expectedCollectionId.equals(actual)) {
                    throw new IllegalStateException("Active collection changed before VACUUM: expected="
                            + expectedCollectionId + ", actual=" + actual);
                }
            }
            collectionStorageManager.vacuumCurrent();
            log.info("Vacuum completed for active collection");
        }
    }

}