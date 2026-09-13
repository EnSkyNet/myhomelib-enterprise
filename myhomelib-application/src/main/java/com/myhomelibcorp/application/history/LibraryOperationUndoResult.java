package com.myhomelibcorp.application.history;

public record LibraryOperationUndoResult(
        String operationId,
        LibraryOperationHistoryType type,
        int affectedCount,
        boolean searchIndexSynchronizedOrScheduled) {
}
