package com.myhomelibcorp.infrastructure.persistence.sqlite;

/** Optimistic concurrency failure: the caller must re-read or seek manual conflict resolution. */
public final class ReadingProgressRevisionConflictException extends com.myhomelibcorp.application.exception.ReadingProgressConflictException {
    public ReadingProgressRevisionConflictException(String bookId) {
        super("Reading progress changed concurrently; refusing stale update (book=" + bookId + ")");
    }
}
