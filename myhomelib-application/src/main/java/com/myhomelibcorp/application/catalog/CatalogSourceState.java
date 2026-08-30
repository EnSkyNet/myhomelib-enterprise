package com.myhomelibcorp.application.catalog;

/** Durable synchronization metadata for one catalog source. */
public record CatalogSourceState(
        String sourceKey,
        String sourceLocation,
        String profileType,
        String appliedVersion,
        String remoteVersion,
        String etag,
        String lastModified,
        String sha256,
        String datasetSchema,
        String lastError
) {
    public static CatalogSourceState empty(String sourceKey) {
        return new CatalogSourceState(sourceKey, "", "", "", "", "", "", "", "", "");
    }
}
