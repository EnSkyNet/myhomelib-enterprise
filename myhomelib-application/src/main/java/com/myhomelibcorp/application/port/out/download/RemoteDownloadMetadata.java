package com.myhomelibcorp.application.port.out.download;

/** Integrity/cache metadata captured for a successfully validated download. */
public record RemoteDownloadMetadata(
        String etag,
        String lastModified,
        String sha256,
        long contentLength,
        String datasetSchema
) {
    public static RemoteDownloadMetadata empty() { return new RemoteDownloadMetadata("", "", "", -1, ""); }
}
