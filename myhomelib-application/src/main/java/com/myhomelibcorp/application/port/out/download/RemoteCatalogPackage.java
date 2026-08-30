package com.myhomelibcorp.application.port.out.download;

import java.nio.file.Path;

/** One downloaded and validated catalog package ready for import. */
public record RemoteCatalogPackage(
        Path file,
        String sourceUrl,
        String version,
        boolean fullSnapshot,
        RemoteDownloadMetadata metadata
) {
    /** Compatibility constructor for existing callers/tests. */
    public RemoteCatalogPackage(Path file, String sourceUrl, String version, boolean fullSnapshot) {
        this(file, sourceUrl, version, fullSnapshot, RemoteDownloadMetadata.empty());
    }

    public RemoteCatalogPackage {
        metadata = metadata == null ? RemoteDownloadMetadata.empty() : metadata;
    }
}
