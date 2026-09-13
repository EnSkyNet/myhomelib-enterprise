package com.myhomelibcorp.application.duplicate.merge;

import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.time.Instant;

/** Minimal durable journal projection used to offer undo after restart. */
public record BookMergeJournalEntry(
        String mergeId,
        BookId survivorBookId,
        BookId mergedBookId,
        BookId metadataSourceBookId,
        Instant mergedAt
) {}
