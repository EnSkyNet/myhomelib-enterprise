package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@RequiredArgsConstructor
@Slf4j
public class SwitchCollectionUseCase {

    private final CollectionRepository collectionRepository;
    private final CollectionLifecycleService collectionLifecycleService;
    private final LibraryOperationCoordinator operationCoordinator;

    public Collection execute(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) {
            throw new IllegalArgumentException("ID колекції не може бути порожнім");
        }

        Optional<Collection> collectionOpt = collectionRepository.findById(collectionId);
        if (collectionOpt.isEmpty()) {
            throw new IllegalArgumentException("Колекцію не знайдено: " + collectionId);
        }

        Collection collection = collectionOpt.get();
        return execute(collection);
    }

    public Collection execute(Collection collection) {
        return execute(collection, true);
    }

    public Collection execute(Collection collection, boolean rebuildIndex) {
        try (var ignored = operationCoordinator.acquire(LibraryOperationType.SWITCH)) {
            return executeLocked(collection, rebuildIndex);
        }
    }

    private Collection executeLocked(Collection collection, boolean rebuildIndex) {
        if (collection == null) {
            throw new IllegalArgumentException("Колекція не може бути null");
        }

        // UI DTO та зовнішні виклики можуть містити лише частину metadata.
        // Якщо ID відомий, завжди беремо авторитетний запис із metadata-БД,
        // щоб не втратити URL/login/password/notes під час активації.
        Collection target = collection;
        if (collection.getId() != null && !collection.getId().isBlank()) {
            target = collectionRepository.findById(collection.getId()).orElse(collection);
        }

        log.info("🔄 Переключення на колекцію: {}", target.getName());

        // Перевіряємо, чи це вже поточна колекція
        Collection current = collectionLifecycleService.getCurrentCollection();
        if (current != null && current.getId() != null && current.getId().equals(target.getId())) {
            // Репозиторій міг бути оновлений (rename/properties), тому освіжаємо
            // descriptor без перестворення DataSource.
            collectionLifecycleService.updateCurrentCollection(target);
            log.info("Колекція {} вже активна; metadata синхронізовано", target.getName());
            return target;
        }

        // Виконуємо повну ініціалізацію з передачею прапорця перебудови індексу
        collectionLifecycleService.initializeCollection(target, rebuildIndex);
        return target;
    }
}