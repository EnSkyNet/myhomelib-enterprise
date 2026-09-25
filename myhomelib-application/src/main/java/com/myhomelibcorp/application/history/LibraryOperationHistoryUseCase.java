package com.myhomelibcorp.application.history;

import com.myhomelibcorp.application.bulkedit.BatchMetadataEditProgress;
import com.myhomelibcorp.application.bulkedit.BatchMetadataEditUseCase;
import com.myhomelibcorp.application.port.out.history.OperationHistoryPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.usecase.duplicate.MergeBooksUseCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Shared MHL-112 history facade spanning bulk metadata and logical-book merge. */
@Service
public class LibraryOperationHistoryUseCase {
    private final OperationHistoryPort history;
    private final BatchMetadataEditUseCase batchEdit;
    private final MergeBooksUseCase mergeBooks;
    private final LibraryOperationCoordinator operations;
    private final CollectionLifecyclePort collections;

    /** Compatibility constructor for focused non-Spring tests. */
    public LibraryOperationHistoryUseCase(OperationHistoryPort history,
                                          BatchMetadataEditUseCase batchEdit,
                                          MergeBooksUseCase mergeBooks,
                                          LibraryOperationCoordinator operations) {
        this(history, batchEdit, mergeBooks, operations, null);
    }

    @Autowired
    public LibraryOperationHistoryUseCase(OperationHistoryPort history,
                                          BatchMetadataEditUseCase batchEdit,
                                          MergeBooksUseCase mergeBooks,
                                          LibraryOperationCoordinator operations,
                                          CollectionLifecyclePort collections) {
        this.history = Objects.requireNonNull(history);
        this.batchEdit = Objects.requireNonNull(batchEdit);
        this.mergeBooks = Objects.requireNonNull(mergeBooks);
        this.operations = Objects.requireNonNull(operations);
        this.collections = collections;
    }

    public Optional<LibraryOperationHistoryEntry> latestUndoable() {
        return withQueryLease(history::latestUndoable);
    }

    public List<LibraryOperationHistoryEntry> recent(int limit) {
        return withQueryLease(() -> history.recent(limit));
    }

    public LibraryOperationUndoResult undoLatest(String collectionId, AtomicBoolean cancelled,
                                                  Consumer<BatchMetadataEditProgress> progress) {
        LibraryOperationHistoryEntry entry = latestUndoable()
                .orElseThrow(() -> new IllegalStateException("No undoable library operation"));
        return undo(collectionId, entry.operationId(), cancelled, progress);
    }

    /** Undo exactly the operation the user reviewed; refuses if a newer reversible operation appeared meanwhile. */
    public LibraryOperationUndoResult undo(String collectionId, String expectedOperationId, AtomicBoolean cancelled,
                                           Consumer<BatchMetadataEditProgress> progress) {
        if (expectedOperationId == null || expectedOperationId.isBlank()) {
            throw new IllegalArgumentException("expectedOperationId is required");
        }
        LibraryOperationHistoryEntry entry = latestUndoable()
                .orElseThrow(() -> new IllegalStateException("No undoable library operation"));
        if (!expectedOperationId.equals(entry.operationId())) {
            throw new IllegalStateException("A newer reversible library operation exists; review undo again");
        }
        return switch (entry.type()) {
            case BULK_METADATA -> {
                var result = batchEdit.undo(collectionId, entry.operationId(), cancelled, progress);
                yield new LibraryOperationUndoResult(entry.operationId(), entry.type(), result.changedCount(), true);
            }
            case BOOK_MERGE -> {
                if (cancelled != null && cancelled.get()) throw new java.util.concurrent.CancellationException("Undo cancelled");
                var lease = operations.acquireDetachedShared(LibraryOperationType.EDIT);
                try {
                    requireCollection(collectionId);
                    LibraryOperationHistoryEntry latest = history.latestUndoable()
                            .orElseThrow(() -> new IllegalStateException("No undoable library operation"));
                    if (!entry.operationId().equals(latest.operationId())) {
                        throw new IllegalStateException("A newer reversible library operation exists; review undo again");
                    }
                    var result = mergeBooks.undo(entry.operationId());
                    lease.markCompleted("");
                    yield new LibraryOperationUndoResult(entry.operationId(), entry.type(), 2, result.searchIndexSynchronized());
                } catch (RuntimeException | Error failure) {
                    lease.markFailed(failure);
                    throw failure;
                } finally {
                    lease.close();
                }
            }
        };
    }
    private <T> T withQueryLease(java.util.function.Supplier<T> action) {
        var lease = operations.acquireDetachedShared(LibraryOperationType.QUERY);
        try {
            T result = action.get();
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private void requireCollection(String expectedCollectionId) {
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) {
            throw new IllegalArgumentException("collectionId is required");
        }
        if (collections == null) return; // compatibility-only constructor used by focused unit tests
        var current = collections.getCurrentCollection();
        if (current == null || current.getId() == null
                || !expectedCollectionId.trim().equals(current.getId().trim())) {
            throw new IllegalStateException("Active collection changed before undo");
        }
    }

}
