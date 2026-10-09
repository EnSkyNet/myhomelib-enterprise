package com.myhomelibcorp.application.kobo;

import java.time.Instant;

/** Device-originated progress update plus the server version/cursor observed by that device. */
public record KoboProgressUpdate(
        String bookId,
        double percent,
        String status,
        String location,
        Instant modifiedAt,
        long baseVersion
) {
    public KoboProgressUpdate {
        if (bookId == null || bookId.isBlank()) throw new IllegalArgumentException("bookId is required");
        if (!Double.isFinite(percent)) throw new IllegalArgumentException("percent must be finite");
        percent = Math.max(0.0, Math.min(100.0, percent));
        status = status == null ? "Reading" : status;
        location = location == null ? "" : location;
        modifiedAt = modifiedAt == null ? Instant.EPOCH : modifiedAt;
        if (baseVersion < 0) throw new IllegalArgumentException("baseVersion cannot be negative");
    }
}
