package com.myhomelibcorp.application.port.out.download;

/** Integrity/cache metadata captured for a successfully validated download. */
public interface RemoteDownloadMetadata {
    String etag();
    String lastModified();
    String sha256();
    long contentLength();
    String datasetSchema();

    static RemoteDownloadMetadata empty() {
        return new RemoteDownloadMetadataRecord("", "", "", -1, "");
    }

    static RemoteDownloadMetadata of(String etag, String lastModified, String sha256, long contentLength, String datasetSchema) {
        return new RemoteDownloadMetadataRecord(etag, lastModified, sha256, contentLength, datasetSchema);
    }
}

/** Internal record implementation. */
record RemoteDownloadMetadataRecord(
        String etag,
        String lastModified,
        String sha256,
        long contentLength,
        String datasetSchema
) implements RemoteDownloadMetadata {}