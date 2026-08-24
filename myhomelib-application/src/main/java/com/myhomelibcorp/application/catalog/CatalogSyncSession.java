package com.myhomelibcorp.application.catalog;

/** Immutable state of one logical INPX synchronization transaction. */
public record CatalogSyncSession(
        String sourceId,
        String sourceKey,
        long sourceRevision,
        String sourceFingerprint,
        boolean initialBaseline,
        boolean sourceChanged
) {
    public CatalogSyncSession {
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId is required");
        if (sourceKey == null || sourceKey.isBlank()) throw new IllegalArgumentException("sourceKey is required");
        if (sourceRevision < 1) throw new IllegalArgumentException("sourceRevision must be >= 1");
        if (sourceFingerprint == null || sourceFingerprint.isBlank()) throw new IllegalArgumentException("sourceFingerprint is required");
    }
}
