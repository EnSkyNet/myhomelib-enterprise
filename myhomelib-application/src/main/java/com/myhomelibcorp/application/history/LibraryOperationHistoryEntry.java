package com.myhomelibcorp.application.history;

import java.time.Instant;

public record LibraryOperationHistoryEntry(
        String operationId,
        LibraryOperationHistoryType type,
        String summary,
        int affectedCount,
        int changedCount,
        Instant createdAt,
        Instant completedAt,
        Instant undoneAt) {
    public boolean undoable() {
        return completedAt != null && undoneAt == null && changedCount > 0;
    }
}
