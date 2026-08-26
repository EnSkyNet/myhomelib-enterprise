package com.myhomelibcorp.application.port.out.download;

import java.nio.file.Path;

/**
 * One downloaded catalog package ready for import.
 *
 * @param file local temporary file; the downloader guarantees it is a valid INPX-style ZIP
 * @param sourceUrl effective remote URL used for this package
 * @param version remote catalog data version (yyyyMMdd when known)
 * @param fullSnapshot true for a full catalog snapshot, false for an incremental update
 */
public record RemoteCatalogPackage(
        Path file,
        String sourceUrl,
        String version,
        boolean fullSnapshot
) {
}
