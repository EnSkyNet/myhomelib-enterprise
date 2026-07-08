package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeleteCollectionUseCase {
    private final CollectionRepository collectionRepository;

    public void execute(String id) {
        collectionRepository.deleteById(id);
    }
}