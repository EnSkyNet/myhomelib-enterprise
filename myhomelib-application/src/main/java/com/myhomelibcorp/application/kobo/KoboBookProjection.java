package com.myhomelibcorp.application.kobo;

import java.time.Instant;

/** Protocol-neutral library projection exposed to a Kobo adapter. */
public record KoboBookProjection(
        String bookId,
        String revision,
        String title,
        String authors,
        String series,
        Integer sequenceNumber,
        String artifactId,
        String artifactRevision,
        String format,
        String downloadName,
        Instant modifiedAt
) {
    public KoboBookProjection {
        bookId = require(bookId, "bookId");
        revision = require(revision, "revision");
        title = text(title);
        authors = text(authors);
        series = text(series);
        artifactId = text(artifactId);
        artifactRevision = text(artifactRevision);
        format = text(format).toLowerCase(java.util.Locale.ROOT);
        downloadName = text(downloadName);
        modifiedAt = modifiedAt == null ? Instant.EPOCH : modifiedAt;
    }

    private static String text(String value) { return value == null ? "" : value; }
    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
