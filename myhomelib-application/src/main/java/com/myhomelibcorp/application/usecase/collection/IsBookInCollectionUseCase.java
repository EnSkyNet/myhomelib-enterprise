package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IsBookInCollectionUseCase {

    private final CollectionRepository collectionRepository;

    public boolean execute(String collectionId, String bookId) {
        return collectionRepository.isBookInCollection(collectionId, bookId);
    }
}