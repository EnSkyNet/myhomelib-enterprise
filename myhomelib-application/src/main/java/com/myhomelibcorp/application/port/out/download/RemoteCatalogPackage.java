package com.myhomelibcorp.application.port.out.download;

import java.nio.file.Path;

/** One downloaded and validated catalog package ready for import. */
public interface RemoteCatalogPackage {
    Path file();
    String sourceUrl();
    String version();
    boolean fullSnapshot();
    RemoteDownloadMetadata metadata();

    /** Compatibility method for existing callers/tests. */
    static RemoteCatalogPackage of(Path file, String sourceUrl, String version, boolean fullSnapshot) {
        return new RemoteCatalogPackageRecord(file, sourceUrl, version, fullSnapshot, RemoteDownloadMetadata.empty());
    }

    static RemoteCatalogPackage of(Path file, String sourceUrl, String version, boolean fullSnapshot, RemoteDownloadMetadata metadata) {
        return new RemoteCatalogPackageRecord(file, sourceUrl, version, fullSnapshot, metadata);
    }
}

/** Internal record implementation. */
record RemoteCatalogPackageRecord(
        Path file,
        String sourceUrl,
        String version,
        boolean fullSnapshot,
        RemoteDownloadMetadata metadata
) implements RemoteCatalogPackage {
    RemoteCatalogPackageRecord {
        metadata = metadata == null ? RemoteDownloadMetadata.empty() : metadata;
    }
}