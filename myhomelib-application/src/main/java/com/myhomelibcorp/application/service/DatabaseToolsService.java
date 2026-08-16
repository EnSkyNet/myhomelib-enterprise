package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseToolsService {

    private final IndexRebuilder indexRebuilder;
    private final CollectionStorageManager collectionStorageManager;

    /**
     * Перебудовує пошуковий індекс.
     */
    public void rebuildIndex() {
        log.info("Rebuilding search index...");
        indexRebuilder.rebuildIndex();
        int count = indexRebuilder.getIndexedDocumentCount();
        log.info("Index rebuilt. {} documents indexed.", count);
    }

    /**
     * Отримує кількість проіндексованих документів.
     */
    public int getIndexedDocumentCount() {
        return indexRebuilder.getIndexedDocumentCount();
    }

    /**
     * Виконує VACUUM на базі даних колекції.
     */
    public void vacuum(Collection collection) {
        collectionStorageManager.vacuum(collection);
        log.info("Vacuum completed for collection: {}", collection.getName());
    }

    /**
     * Закриває колекцію.
     */
    public void closeCollection(Collection collection) {
        collectionStorageManager.closeCollection(collection);
        log.info("Closed collection: {}", collection.getName());
    }
}