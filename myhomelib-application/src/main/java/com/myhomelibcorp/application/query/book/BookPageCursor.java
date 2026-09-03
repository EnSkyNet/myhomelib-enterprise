package com.myhomelibcorp.application.query.book;

/**
 * Stable title-order cursor for bounded catalog paging.
 * Title is non-null by schema and id is the deterministic tie-breaker.
 */
public record BookPageCursor(String title, String id) {
    public BookPageCursor {
        if (title == null) throw new IllegalArgumentException("title cannot be null");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id cannot be blank");
    }
}
