package com.myhomelibcorp.application.usecase.duplicate;

import com.myhomelibcorp.application.duplicate.merge.BookMergeJournalEntry;
import com.myhomelibcorp.application.duplicate.merge.BookMergeMutationResult;
import com.myhomelibcorp.application.duplicate.merge.BookMergePlan;
import com.myhomelibcorp.application.duplicate.merge.BookMergeResult;
import com.myhomelibcorp.application.port.out.duplicate.BookMergePort;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Explicit logical-book merge plus durable undo. Physical files are intentionally outside this use case. */
@Component
@RequiredArgsConstructor
public class MergeBooksUseCase {
    private final BookMergePort mergePort;
    private final SearchIndexSynchronizer searchIndexSynchronizer;

    public BookMergeResult merge(BookMergePlan plan) {
        BookMergeMutationResult mutation = mergePort.merge(plan);
        boolean searchOk = searchIndexSynchronizer.synchronizeSafelyNow(
                List.of(mutation.survivorBookId(), mutation.mergedBookId()));
        return result(mutation, searchOk);
    }

    public BookMergeResult undo(String mergeId) {
        if (mergeId == null || mergeId.isBlank()) throw new IllegalArgumentException("mergeId is required");
        BookMergeMutationResult mutation = mergePort.undo(mergeId);
        boolean searchOk = searchIndexSynchronizer.synchronizeSafelyNow(
                List.of(mutation.survivorBookId(), mutation.mergedBookId()));
        return result(mutation, searchOk);
    }

    public Optional<BookMergeJournalEntry> latestActiveMerge(BookId bookId) {
        if (bookId == null) return Optional.empty();
        return mergePort.findLatestActiveMerge(bookId);
    }

    private static BookMergeResult result(BookMergeMutationResult mutation, boolean searchOk) {
        return new BookMergeResult(mutation.mergeId(), mutation.survivorBookId(), mutation.mergedBookId(),
                mutation.changedAt(), mutation.undone(), searchOk);
    }
}
