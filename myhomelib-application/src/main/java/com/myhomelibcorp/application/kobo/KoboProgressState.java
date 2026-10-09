package com.myhomelibcorp.application.kobo;

import java.time.Instant;

/** Canonical protocol-neutral progress state. Percent is in the 0..100 range. */
public record KoboProgressState(
        String bookId,
        double percent,
        String status,
        String location,
        Instant modifiedAt,
        long version
) {
    public KoboProgressState {
        if (bookId == null || bookId.isBlank()) throw new IllegalArgumentException("bookId is required");
        if (!Double.isFinite(percent)) percent = 0.0;
        percent = Math.max(0.0, Math.min(100.0, percent));
        status = status == null ? "ReadyToRead" : status;
        location = location == null ? "" : location;
        modifiedAt = modifiedAt == null ? Instant.EPOCH : modifiedAt;
        if (version < 0) throw new IllegalArgumentException("version cannot be negative");
    }
}
