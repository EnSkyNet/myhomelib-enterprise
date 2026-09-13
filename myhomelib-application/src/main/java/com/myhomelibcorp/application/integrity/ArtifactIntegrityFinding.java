package com.myhomelibcorp.application.integrity;

import java.time.Instant;

/** User-facing audit detail without infrastructure/path implementation types. */
public record ArtifactIntegrityFinding(
        String artifactId,
        String bookId,
        String bookTitle,
        String path,
        ArtifactIntegrityStatus status,
        long baselineSizeBytes,
        long observedSizeBytes,
        String baselineSha256,
        String observedSha256,
        String detail,
        Instant checkedAt
) {
    public ArtifactIntegrityFinding {
        artifactId = safe(artifactId);
        bookId = safe(bookId);
        bookTitle = safe(bookTitle);
        path = safe(path);
        status = status == null ? ArtifactIntegrityStatus.UNREADABLE : status;
        baselineSha256 = safe(baselineSha256);
        observedSha256 = safe(observedSha256);
        detail = safe(detail);
        checkedAt = checkedAt == null ? Instant.now() : checkedAt;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
