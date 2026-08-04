package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class LoadCollectionsUseCase {

    private final CollectionRepository collectionRepository;

    public List<CollectionDto> execute() {
        return collectionRepository.findAll().stream()
                .map(collection -> new CollectionDto(
                        collection.getId(),
                        collection.getName(),
                        true // або визначити за логікою
                ))
                .collect(Collectors.toList());
    }
}