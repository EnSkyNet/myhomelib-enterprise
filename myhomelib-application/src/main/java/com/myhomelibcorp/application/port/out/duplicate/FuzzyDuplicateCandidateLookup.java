package com.myhomelibcorp.application.port.out.duplicate;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;

/**
 * Bounded blocking lookup for fuzzy duplicate review. Implementations must return at most {@code limit}
 * candidates and must not materialize the whole catalogue or perform one query per catalogue row.
 */
public interface FuzzyDuplicateCandidateLookup {
    List<BookId> findCandidateIds(Book source, int limit);
}
