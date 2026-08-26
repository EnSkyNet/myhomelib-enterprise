package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
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
    private final CollectionLifecyclePort collectionLifecyclePort;

    public void execute(String id) {
        Collection collection = collectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Колекцію не знайдено: " + id));

        Collection active = collectionLifecyclePort.getCurrentCollection();
        if (active != null && active.getId().equals(collection.getId())) {
            throw new IllegalStateException("Активну колекцію не можна видалити. Спочатку перемкніться на іншу.");
        }
        if (collectionRepository.findAll().size() <= 1) {
            throw new IllegalStateException("Не можна видалити останню колекцію.");
        }

        storageManager.closeCollection(collection);
        storageManager.vacuum(collection);
        storageManager.deletePhysicalFiles(collection);
        collectionRepository.deleteById(id);
        log.info("Колекцію видалено: {}", id);
    }
}