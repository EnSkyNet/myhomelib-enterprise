package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;
import java.util.Map;

/**
 * Bounded bulk lookup for catalogue duplicates.
 * Implementations must not degrade a large import batch into one DB round-trip per book.
 */
public interface DuplicateBookLookup {
    Map<DuplicateBookCandidate, BookId> findDuplicateIds(List<DuplicateBookCandidate> candidates);
}
