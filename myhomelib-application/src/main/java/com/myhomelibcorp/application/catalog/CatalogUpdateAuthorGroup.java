package com.myhomelibcorp.application.catalog;

import java.util.List;

/** One author branch in the Stage 7 Updates tree. */
public record CatalogUpdateAuthorGroup(
        String authorId,
        String authorName,
        List<CatalogUpdateItem> newBooks,
        List<CatalogUpdateItem> updatedBooks
) {
    public CatalogUpdateAuthorGroup {
        authorId = authorId == null ? "" : authorId;
        authorName = authorName == null || authorName.isBlank() ? "Без автора" : authorName;
        newBooks = newBooks == null ? List.of() : List.copyOf(newBooks);
        updatedBooks = updatedBooks == null ? List.of() : List.copyOf(updatedBooks);
    }

    public long totalCount() {
        return (long) newBooks.size() + updatedBooks.size();
    }
}
