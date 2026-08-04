package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RemoveBookFromCollectionUseCase {

    private final CollectionRepository collectionRepository;

    public void execute(String collectionId, String bookId) {
        if (collectionId == null || bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("Collection ID and Book ID cannot be null");
        }
        collectionRepository.removeBookFromCollection(collectionId, bookId);
    }
}