package com.myhomelibcorp.application.duplicate.merge;

import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.time.Instant;

/** User-facing merge/undo result. DB commit is authoritative even if derived Lucene refresh falls back/fails. */
public record BookMergeResult(
        String mergeId,
        BookId survivorBookId,
        BookId mergedBookId,
        Instant changedAt,
        boolean undone,
        boolean searchIndexSynchronized
) {}
