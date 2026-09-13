package com.myhomelibcorp.application.sync.conflict;

/** Explicit user choice returned by the UI without exposing domain records to the UI layer. */
public record SyncConflictReviewSelection(String logicalKey, Side side) {
    public enum Side { LOCAL, REMOTE }

    public SyncConflictReviewSelection {
        if (logicalKey == null || logicalKey.isBlank()) throw new IllegalArgumentException("logicalKey is required");
        if (side == null) throw new IllegalArgumentException("side is required");
    }
}
