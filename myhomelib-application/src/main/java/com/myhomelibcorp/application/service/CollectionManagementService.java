package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
@RequiredArgsConstructor
@Slf4j
public class CollectionManagementService {

    private final CollectionLifecyclePort collectionLifecyclePort;
    private final CollectionStorageManager collectionStorageManager;

    /**
     * Перемикає на вказану колекцію.
     */
    public void switchToCollection(Collection collection) {
        collectionLifecyclePort.switchToCollection(collection);
        log.info("Switched to collection: {}", collection.getName());
    }

    /**
     * Закриває поточну колекцію.
     */
    public void closeCurrentCollection() {
        collectionLifecyclePort.closeCurrentCollection();
        log.info("Closed current collection");
    }

    /**
     * Отримує поточну колекцію.
     */
    public Collection getCurrentCollection() {
        return collectionLifecyclePort.getCurrentCollection();
    }

    /**
     * Перевіряє чи є активна колекція.
     */
    public boolean hasActiveCollection() {
        return collectionLifecyclePort.hasActiveCollection();
    }

    /**
     * Перевіряє чи колекція готова до роботи.
     */
    public boolean isCollectionReady() {
        return collectionLifecyclePort.isCollectionReady();
    }

    /**
     * Отримує розмір бази даних.
     */
    public long getDatabaseSize() {
        return collectionLifecyclePort.getDatabaseSize();
    }

    /**
     * Видаляє фізичні файли колекції.
     */
    public void deletePhysicalFiles(Collection collection) {
        collectionStorageManager.deletePhysicalFiles(collection);
        log.info("Deleted physical files for collection: {}", collection.getName());
    }

    /**
     * Виконує VACUUM на базі даних колекції.
     */
    public void vacuum(Collection collection) {
        collectionStorageManager.vacuum(collection);
        log.info("Vacuum completed for collection: {}", collection.getName());
    }

    /**
     * Закриває колекцію (закриває з'єднання).
     */
    public void closeCollection(Collection collection) {
        collectionStorageManager.closeCollection(collection);
        log.info("Closed collection: {}", collection.getName());
    }
}