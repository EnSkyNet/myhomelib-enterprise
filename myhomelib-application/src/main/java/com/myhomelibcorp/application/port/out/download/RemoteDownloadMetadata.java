package com.myhomelibcorp.application.port.out.download;

/** Integrity/cache metadata captured for a successfully validated download. */
public interface RemoteDownloadMetadata {
    String etag();
    String lastModified();
    String sha256();
    long contentLength();
    String datasetSchema();

    static RemoteDownloadMetadata empty() {
        return new Impl("", "", "", -1, "");
    }

    static RemoteDownloadMetadata of(String etag, String lastModified, String sha256, long contentLength, String datasetSchema) {
        return new Impl(etag, lastModified, sha256, contentLength, datasetSchema);
    }

    /** Internal implementation - not part of the public API. */
    record Impl(
            String etag,
            String lastModified,
            String sha256,
            long contentLength,
            String datasetSchema
    ) implements RemoteDownloadMetadata {}
}