package com.myhomelibcorp.application.port.out.history;

import com.myhomelibcorp.application.bulkedit.BatchMetadataChange;
import com.myhomelibcorp.application.bulkedit.BatchMetadataEditRule;
import com.myhomelibcorp.application.history.LibraryOperationHistoryEntry;

import java.util.List;
import java.util.Optional;

/** Shared durable journal for reversible collection mutations. */
public interface OperationHistoryPort {
    void beginBulkOperation(String operationId, String summary, int selectedCount, List<BatchMetadataEditRule> rules);
    void appendBulkChanges(String operationId, int sequenceStart, List<BatchMetadataChange> changes);
    void completeOperation(String operationId, int changedCount);
    void markUndone(String operationId);
    Optional<LibraryOperationHistoryEntry> latestUndoable();
    List<LibraryOperationHistoryEntry> recent(int limit);
    List<BatchMetadataChange> loadBulkChanges(String operationId, int offset, int limit);
    int countBulkChanges(String operationId);
    void requireLatestUndoable(String operationId);
    void prune(int retainedOperations);
}
