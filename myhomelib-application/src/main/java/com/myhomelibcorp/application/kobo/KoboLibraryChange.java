package com.myhomelibcorp.application.kobo;

/** One incremental library mutation visible to a Kobo client. */
public record KoboLibraryChange(long cursor, Kind kind, KoboBookProjection book, String bookId) {
    public enum Kind { ADD, UPDATE, REMOVE }

    public KoboLibraryChange {
        if (cursor <= 0) throw new IllegalArgumentException("Change cursor must be positive");
        kind = java.util.Objects.requireNonNull(kind, "kind");
        bookId = bookId == null ? "" : bookId;
        if ((kind == Kind.ADD || kind == Kind.UPDATE) && book == null) throw new IllegalArgumentException("Book projection is required");
        if (kind == Kind.REMOVE && bookId.isBlank()) throw new IllegalArgumentException("Book id is required for remove");
    }
}
