package com.myhomelibcorp.application.duplicate.merge;

import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.time.Instant;

/** Authoritative SQLite mutation result. Search-index synchronization happens after this commit. */
public record BookMergeMutationResult(
        String mergeId,
        BookId survivorBookId,
        BookId mergedBookId,
        Instant changedAt,
        boolean undone
) {
    public BookMergeMutationResult {
        if (mergeId == null || mergeId.isBlank()) throw new IllegalArgumentException("mergeId is required");
        if (survivorBookId == null || mergedBookId == null) throw new IllegalArgumentException("book ids are required");
        if (changedAt == null) changedAt = Instant.now();
    }
}
