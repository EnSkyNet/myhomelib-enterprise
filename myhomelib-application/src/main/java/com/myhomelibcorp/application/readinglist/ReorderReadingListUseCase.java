package com.myhomelibcorp.application.readinglist;

import com.myhomelibcorp.application.port.out.repository.ReadingListRepository;

import com.myhomelibcorp.domain.model.readinglist.ReadingListId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component @RequiredArgsConstructor
public class ReorderReadingListUseCase {
    private final ReadingListRepository repository;
    private final ReadingListMutationExecutor mutation;
    public void execute(String id, List<BookId> orderedBookIds, String expectedCollectionId) { execute(ReadingListId.fromString(id), orderedBookIds, expectedCollectionId); }
    public void execute(ReadingListId id, List<BookId> orderedBookIds, String expectedCollectionId) {
        if (orderedBookIds == null) throw new IllegalArgumentException("Порядок не задано");
        if (new HashSet<>(orderedBookIds).size() != orderedBookIds.size()) throw new IllegalArgumentException("Порядок містить дублікати книг");
        mutation.execute(expectedCollectionId, () -> repository.reorder(id, List.copyOf(orderedBookIds)));
    }
}
