package com.myhomelibcorp.application.catalog;

import java.util.List;

/** Immutable snapshot used by the Updates workspace. */
public record CatalogUpdateSnapshot(
        long totalCount,
        long newCount,
        long updatedCount,
        List<CatalogUpdateAuthorGroup> authors
) {
    public CatalogUpdateSnapshot {
        authors = authors == null ? List.of() : List.copyOf(authors);
    }

    public static CatalogUpdateSnapshot empty() {
        return new CatalogUpdateSnapshot(0, 0, 0, List.of());
    }
}
