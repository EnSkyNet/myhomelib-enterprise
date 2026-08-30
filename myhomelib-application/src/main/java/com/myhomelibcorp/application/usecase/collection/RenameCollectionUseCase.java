package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RenameCollectionUseCase {

    private final CollectionRepository collectionRepository;
    private final CollectionLifecyclePort collectionLifecyclePort;

    public Collection execute(String id, String newName) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Collection id cannot be empty");
        }
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("Назва колекції не може бути порожньою");
        }
        Collection collection = collectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Collection not found"));
        String safeName = newName.trim();
        collectionRepository.findByName(safeName)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Колекція з назвою '" + safeName + "' вже існує");
                });
        Collection renamed = new Collection(
                collection.getId(),
                safeName,
                collection.getRootFolder(),
                collection.getDbFile(),
                collection.getType(),
                collection.getUser(),
                collection.getPassword(),
                collection.getUrl(),
                collection.getNotes(),
                collection.getConnectionScript()
        );
        Collection saved = collectionRepository.save(renamed);
        Collection active = collectionLifecyclePort.getCurrentCollection();
        if (active != null && active.getId() != null && active.getId().equals(saved.getId())) {
            collectionLifecyclePort.updateCurrentCollection(saved);
        }
        return saved;
    }
}