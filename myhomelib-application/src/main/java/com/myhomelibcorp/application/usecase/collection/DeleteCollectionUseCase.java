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

        // 1. Закриваємо всі ресурси
        storageManager.closeCollection(collection);

        // 2. Виконуємо VACUUM (якщо потрібно)
        storageManager.vacuum(collection);

        // 3. Видаляємо всі фізичні файли
        storageManager.deletePhysicalFiles(collection);

        // 4. Видаляємо запис з мета-БД
        collectionRepository.deleteById(id);
        log.info("Колекцію видалено: {}", id);
    }
}