package com.myhomelibcorp.application.catalog;

/** Pending catalog update exposed by the data layer for the Stage 7 UI. */
public record CatalogUpdateRecord(
        String bookId,
        CatalogUpdateType type,
        String sourceId,
        long detectedRevision,
        String catalogFingerprint,
        String detectedAt
) { }
