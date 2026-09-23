package com.myhomelibcorp.application.catalog;

/**
 * UI-agnostic pending update item with the author chosen for hierarchical grouping.
 * authorId may be empty for malformed/legacy rows without author links.
 */
public record CatalogUpdateItem(
        String bookId,
        String bookTitle,
        String authorId,
        String authorName,
        CatalogUpdateType type,
        boolean local,
        String detectedAt
) {
    public CatalogUpdateItem {
        bookId = bookId == null ? "" : bookId;
        bookTitle = bookTitle == null ? "" : bookTitle;
        authorId = authorId == null ? "" : authorId;
        authorName = authorName == null ? "" : authorName;
        detectedAt = detectedAt == null ? "" : detectedAt;
    }
}
