package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.port.out.collection.CollectionSourceMonitorPort;
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
    private final CollectionSourceMonitorPort sourceMonitorPort;

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

        // Stop in-process source monitoring as part of the collection lifecycle, not as a UI side effect.
        sourceMonitorPort.stopMonitoring(id);
        storageManager.closeCollection(collection);
        // VACUUM must not run here: the collection being deleted is necessarily inactive,
        // while the storage adapter's VACUUM operates on the active JdbcTemplate.
        // Compacting a database immediately before deleting its file is unnecessary anyway.
        storageManager.deletePhysicalFiles(collection);
        collectionRepository.deleteById(id);
        log.info("Колекцію видалено: {}", id);
    }
}