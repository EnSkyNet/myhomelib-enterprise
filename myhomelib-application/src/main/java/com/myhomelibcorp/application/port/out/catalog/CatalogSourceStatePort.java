package com.myhomelibcorp.application.port.out.catalog;

import com.myhomelibcorp.application.catalog.CatalogSourceState;

/** Persistence boundary for durable remote catalog synchronization state. */
public interface CatalogSourceStatePort {
    CatalogSourceState get(String sourceKey);

    void recordChecked(String sourceKey, String sourceLocation, String profileType, String remoteVersion);

    void recordDownloaded(String sourceKey, String etag, String lastModified, String sha256, String datasetSchema);

    /** Advance appliedVersion only after DB changes and search-index finalization succeeded. */
    void recordApplied(String sourceKey, String appliedVersion);

    void recordFailure(String sourceKey, String message);
}
