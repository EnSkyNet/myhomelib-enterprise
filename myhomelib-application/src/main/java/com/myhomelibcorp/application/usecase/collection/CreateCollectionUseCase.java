package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@RequiredArgsConstructor
public class CreateCollectionUseCase {

    private final CollectionRepository collectionRepository;

    public Collection execute(String name, String rootFolderPath) {
        Path rootFolder = rootFolderPath != null ? Paths.get(rootFolderPath) : null;
        Collection collection = new Collection(null, name, rootFolder, null, 0, null, null, null, null);
        return collectionRepository.save(collection);
    }
}