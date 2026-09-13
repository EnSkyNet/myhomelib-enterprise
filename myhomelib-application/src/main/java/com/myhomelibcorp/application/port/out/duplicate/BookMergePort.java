package com.myhomelibcorp.application.port.out.duplicate;

import com.myhomelibcorp.application.duplicate.merge.BookMergeJournalEntry;
import com.myhomelibcorp.application.duplicate.merge.BookMergeMutationResult;
import com.myhomelibcorp.application.duplicate.merge.BookMergePlan;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.Optional;

/** Atomic persistent merge/undo boundary. Implementations must never delete physical book files. */
public interface BookMergePort {
    BookMergeMutationResult merge(BookMergePlan plan);
    BookMergeMutationResult undo(String mergeId);
    Optional<BookMergeJournalEntry> findLatestActiveMerge(BookId bookId);
}
