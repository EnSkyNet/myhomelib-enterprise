package com.myhomelibcorp.application.port.out.catalog;

import com.myhomelibcorp.application.catalog.CatalogSourceState;

/** Persistence boundary for durable remote catalog synchronization state. */
public interface CatalogSourceStatePort {
    CatalogSourceState get(String sourceKey);

    void recordChecked(String sourceKey, String sourceLocation, String profileType, String remoteVersion);

    void recordDownloaded(String sourceKey, String etag, String lastModified, String sha256, String datasetSchema);

    /**
     * True only when the catalog source fingerprint committed by the last successful import
     * exactly matches the supplied SHA-256. Download metadata is deliberately not used here:
     * a previously downloaded package may have failed before its catalog mutation committed.
     */
    boolean matchesAppliedFingerprint(String sourceKey, String sha256);

    /** Advance appliedVersion only after DB changes and search-index finalization succeeded. */
    void recordApplied(String sourceKey, String appliedVersion);

    void recordFailure(String sourceKey, String message);
}
