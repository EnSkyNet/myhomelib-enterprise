package com.myhomelibcorp.application.catalog;

/** Stable keyset cursor for pending catalog updates ordered by detectedAt DESC, bookId/type ASC. */
public record CatalogUpdateCursor(String detectedAt, String bookId, CatalogUpdateType type) {
    public CatalogUpdateCursor {
        detectedAt = detectedAt == null ? "" : detectedAt;
        bookId = bookId == null ? "" : bookId;
        if (type == null) throw new IllegalArgumentException("type cannot be null");
    }

    public static CatalogUpdateCursor after(CatalogUpdateItem item) {
        if (item == null) throw new IllegalArgumentException("item cannot be null");
        return new CatalogUpdateCursor(item.detectedAt(), item.bookId(), item.type());
    }
}
