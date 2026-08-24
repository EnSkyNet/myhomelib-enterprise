package com.myhomelibcorp.application.catalog;

/** Catalog-owned state for one imported book; user/local state intentionally does not belong here. */
public record CatalogBookSnapshot(
        String bookId,
        String sourceBookKey,
        String catalogFingerprint,
        String fileName,
        String folder,
        String archiveEntry,
        long fileSize
) {
    public CatalogBookSnapshot {
        if (bookId == null || bookId.isBlank()) throw new IllegalArgumentException("bookId is required");
        sourceBookKey = sourceBookKey == null ? "" : sourceBookKey;
        if (catalogFingerprint == null || catalogFingerprint.isBlank()) {
            throw new IllegalArgumentException("catalogFingerprint is required");
        }
        fileName = fileName == null ? "" : fileName;
        folder = folder == null ? "" : folder;
        archiveEntry = archiveEntry == null ? "" : archiveEntry;
        if (fileSize < 0) fileSize = 0;
    }
}
