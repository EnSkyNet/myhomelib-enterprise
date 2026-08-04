package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeleteCollectionUseCase {

    private final CollectionRepository collectionRepository;
    private final CollectionStorageManager storageManager;

    public void execute(String id) {
        Collection collection = collectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Колекцію не знайдено: " + id));

        storageManager.closeCollection(collection);
        storageManager.vacuum(collection);
        storageManager.deletePhysicalFiles(collection);
        collectionRepository.deleteById(id);
        log.info("Колекцію видалено: {}", id);
    }
}