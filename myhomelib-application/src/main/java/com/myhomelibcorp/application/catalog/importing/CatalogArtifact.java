package com.myhomelibcorp.application.catalog.importing;

import java.util.Map;

public record CatalogArtifact(
        String name,
        String mediaType,
        String fileFormat,
        String archive,
        String archiveEntry,
        Long size,
        String sha256,
        String contentFingerprint,
        Map<String, String> metadata
) {
    public CatalogArtifact {
        name = safe(name);
        mediaType = safe(mediaType);
        fileFormat = safe(fileFormat);
        archive = safe(archive);
        archiveEntry = safe(archiveEntry);
        sha256 = safe(sha256);
        contentFingerprint = safe(contentFingerprint);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
