package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.application.mapper.CollectionDtoMapper;
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
                    return CollectionDtoMapper.toDto(collection, isActive, canDeleteAny);
                })
                .toList();
    }
}
