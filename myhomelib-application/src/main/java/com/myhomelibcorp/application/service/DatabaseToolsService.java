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
     * Виконує VACUUM на базі даних колекції.
     */
    public void vacuumCurrent() {
        try (var ignored = operationCoordinator.acquire(LibraryOperationType.VACUUM)) {
            collectionStorageManager.vacuumCurrent();
            log.info("Vacuum completed for active collection");
        }
    }

}