package com.myhomelibcorp.application.port.out.download;

import java.nio.file.Path;

/** One downloaded and validated catalog package ready for import. */
public interface RemoteCatalogPackage {
    Path file();
    String sourceUrl();
    String version();
    boolean fullSnapshot();
    RemoteDownloadMetadata metadata();

    static RemoteCatalogPackage of(Path file, String sourceUrl, String version, boolean fullSnapshot) {
        return new Impl(file, sourceUrl, version, fullSnapshot, RemoteDownloadMetadata.empty());
    }

    static RemoteCatalogPackage of(Path file, String sourceUrl, String version, boolean fullSnapshot, RemoteDownloadMetadata metadata) {
        return new Impl(file, sourceUrl, version, fullSnapshot, metadata);
    }

    /** Internal implementation - not part of the public API. */
    record Impl(
            Path file,
            String sourceUrl,
            String version,
            boolean fullSnapshot,
            RemoteDownloadMetadata metadata
    ) implements RemoteCatalogPackage {
        // Public compact constructor
        public Impl {
            metadata = metadata == null ? RemoteDownloadMetadata.empty() : metadata;
        }
    }
}