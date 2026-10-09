package com.myhomelibcorp.application.readinglist;

import com.myhomelibcorp.application.port.out.repository.ReadingListRepository;

import com.myhomelibcorp.domain.model.readinglist.ReadingListId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component @RequiredArgsConstructor
public class RemoveReadingListItemsUseCase {
    private final ReadingListRepository repository;
    private final ReadingListMutationExecutor mutation;
    public void execute(String id, List<BookId> bookIds, String expectedCollectionId) { execute(ReadingListId.fromString(id), bookIds, expectedCollectionId); }
    public void execute(ReadingListId id, List<BookId> bookIds, String expectedCollectionId) {
        List<BookId> unique = bookIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(bookIds.stream().filter(Objects::nonNull).toList()));
        if (unique.isEmpty()) return;
        mutation.execute(expectedCollectionId, () -> repository.removeBooks(id, unique));
    }
}
