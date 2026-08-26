package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadCollectionsUseCase {

    private final CollectionRepository collectionRepository;
    private final CollectionLifecyclePort collectionLifecyclePort;

    public List<CollectionDto> execute() {
        List<Collection> collections = collectionRepository.findAll();
        Collection active = collectionLifecyclePort.getCurrentCollection();
        boolean canDeleteAny = collections.size() > 1;

        return collections.stream()
                .map(collection -> {
                    boolean isActive = active != null && active.getId().equals(collection.getId());
                    return CollectionDto.builder()
                            .id(collection.getId())
                            .name(collection.getName())
                            .active(isActive)
                            .allowRename(true)
                            .allowDelete(canDeleteAny && !isActive)
                            .rootFolder(collection.getRootFolder() == null
                                    ? null : collection.getRootFolder().toString())
                            .dbFile(collection.getDbFile())
                            .type(collection.getType())
                            .booksCount(-1L)
                            .build();
                })
                .toList();
    }
}
