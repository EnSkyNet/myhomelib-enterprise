package com.myhomelibcorp.application.usecase.duplicate;

import com.myhomelibcorp.application.duplicate.merge.BookMergeJournalEntry;
import com.myhomelibcorp.application.duplicate.merge.BookMergeMutationResult;
import com.myhomelibcorp.application.duplicate.merge.BookMergePlan;
import com.myhomelibcorp.application.duplicate.merge.BookMergeResult;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.duplicate.BookMergePort;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Explicit logical-book merge plus durable undo. Physical files are intentionally outside this use case. */
@Component
public class MergeBooksUseCase {
    private final BookMergePort mergePort;
    private final SearchIndexSynchronizer searchIndexSynchronizer;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for focused non-Spring tests. */
    public MergeBooksUseCase(BookMergePort mergePort, SearchIndexSynchronizer searchIndexSynchronizer) {
        this(mergePort, searchIndexSynchronizer, new LibraryOperationCoordinator());
    }

    @Autowired
    public MergeBooksUseCase(BookMergePort mergePort, SearchIndexSynchronizer searchIndexSynchronizer,
                             LibraryOperationCoordinator operationCoordinator) {
        this.mergePort = mergePort;
        this.searchIndexSynchronizer = searchIndexSynchronizer;
        this.operationCoordinator = operationCoordinator;
    }

    public BookMergeResult merge(BookMergePlan plan) {
        return withEditLease(() -> {
            BookMergeMutationResult mutation = mergePort.merge(plan);
            boolean searchOk = searchIndexSynchronizer.synchronizeSafelyNow(
                    List.of(mutation.survivorBookId(), mutation.mergedBookId()));
            return result(mutation, searchOk);
        });
    }

    public BookMergeResult undo(String mergeId) {
        if (mergeId == null || mergeId.isBlank()) throw new IllegalArgumentException("mergeId is required");
        return withEditLease(() -> {
            BookMergeMutationResult mutation = mergePort.undo(mergeId);
            boolean searchOk = searchIndexSynchronizer.synchronizeSafelyNow(
                    List.of(mutation.survivorBookId(), mutation.mergedBookId()));
            return result(mutation, searchOk);
        });
    }

    public Optional<BookMergeJournalEntry> latestActiveMerge(BookId bookId) {
        if (bookId == null) return Optional.empty();
        return mergePort.findLatestActiveMerge(bookId);
    }

    private <T> T withEditLease(java.util.function.Supplier<T> action) {
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.EDIT);
        try {
            T result = action.get();
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private static BookMergeResult result(BookMergeMutationResult mutation, boolean searchOk) {
        return new BookMergeResult(mutation.mergeId(), mutation.survivorBookId(), mutation.mergedBookId(),
                mutation.changedAt(), mutation.undone(), searchOk);
    }
}
