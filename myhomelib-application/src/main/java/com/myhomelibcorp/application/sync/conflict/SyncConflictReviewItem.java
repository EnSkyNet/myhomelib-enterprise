package com.myhomelibcorp.application.sync.conflict;

/** UI-safe projection of one unresolved conflict; contains no domain model references. */
public record SyncConflictReviewItem(
        String logicalKey,
        String entityType,
        String reason,
        String localSnapshot,
        String remoteSnapshot
) {
    public SyncConflictReviewItem {
        if (logicalKey == null || logicalKey.isBlank()) throw new IllegalArgumentException("logicalKey is required");
        if (entityType == null || entityType.isBlank()) throw new IllegalArgumentException("entityType is required");
        reason = reason == null ? "" : reason;
        localSnapshot = localSnapshot == null ? "" : localSnapshot;
        remoteSnapshot = remoteSnapshot == null ? "" : remoteSnapshot;
    }
}
