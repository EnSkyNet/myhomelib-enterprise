package com.myhomelibcorp.application.readinglist;

import com.myhomelibcorp.application.port.out.repository.ReadingListRepository;

import com.myhomelibcorp.domain.model.readinglist.ReadingListId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class DeleteReadingListUseCase {
    private final ReadingListRepository repository;
    private final ReadingListMutationExecutor mutation;
    public void execute(String id, String expectedCollectionId) { execute(ReadingListId.fromString(id), expectedCollectionId); }
    public void execute(ReadingListId id, String expectedCollectionId) {
        mutation.execute(expectedCollectionId, () -> repository.delete(id));
    }
}
