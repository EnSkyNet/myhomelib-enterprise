package com.myhomelibcorp.application.kobo;

/** Monotonic server cursor used for deterministic incremental Kobo synchronization. */
public record KoboSyncCursor(long value) {
    public KoboSyncCursor {
        if (value < 0) throw new IllegalArgumentException("Kobo sync cursor cannot be negative");
    }
    public static KoboSyncCursor initial() { return new KoboSyncCursor(0L); }
}
