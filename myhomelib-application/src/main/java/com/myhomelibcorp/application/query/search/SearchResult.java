package com.myhomelibcorp.application.query.search;

import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;

public record SearchResult(
        List<BookId> bookIds,
        long totalHits,
        int page,
        int pageSize,
        long timeMs
) {
    public static SearchResult empty() {
        return new SearchResult(List.of(), 0, 0, 0, 0);
    }

    public boolean isEmpty() {
        return bookIds.isEmpty();
    }
}