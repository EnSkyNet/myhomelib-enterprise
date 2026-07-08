package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RenameCollectionUseCase {
    private final CollectionRepository collectionRepository;

    public Collection execute(String id, String newName) {
        Collection collection = collectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Collection not found"));
        Collection renamed = new Collection(
                collection.getId(),
                newName,
                collection.getRootFolder(),
                collection.getDbFile(),
                collection.getType(),
                collection.getUser(),
                collection.getPassword(),
                collection.getUrl(),
                collection.getNotes()
        );
        return collectionRepository.save(renamed);
    }
}