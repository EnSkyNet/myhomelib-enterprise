package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Use Case: переключення на іншу колекцію.
 */
@RequiredArgsConstructor
@Slf4j
public class SwitchCollectionUseCase {

    private final CollectionRepository collectionRepository;
    private final CollectionLifecycleService collectionLifecycleService;

    public void execute(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) {
            throw new IllegalArgumentException("ID колекції не може бути порожнім");
        }

        Optional<Collection> collectionOpt = collectionRepository.findById(collectionId);
        if (collectionOpt.isEmpty()) {
            throw new IllegalArgumentException("Колекцію не знайдено: " + collectionId);
        }

        Collection collection = collectionOpt.get();
        execute(collection);
    }

    public void execute(Collection collection) {
        if (collection == null) {
            throw new IllegalArgumentException("Колекція не може бути null");
        }

        log.info("🔄 Переключення на колекцію: {}", collection.getName());

        // Перевіряємо, чи це вже поточна колекція
        Collection current = collectionLifecycleService.getCurrentCollection();
        if (current != null && current.getId().equals(collection.getId())) {
            log.info("Колекція {} вже активна", collection.getName());
            return;
        }

        // Виконуємо повну ініціалізацію
        collectionLifecycleService.initializeCollection(collection, true);
    }
}