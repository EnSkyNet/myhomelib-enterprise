package com.myhomelibcorp.application.history;

import com.myhomelibcorp.application.bulkedit.BatchMetadataEditResult;
import com.myhomelibcorp.application.bulkedit.BatchMetadataEditUseCase;
import com.myhomelibcorp.application.duplicate.merge.BookMergeResult;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.history.OperationHistoryPort;
import com.myhomelibcorp.application.usecase.duplicate.MergeBooksUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LibraryOperationHistoryUseCaseTest {

    @Test
    void reviewedOlderOperationIsRefusedWhenNewerUndoableEntryAppears() {
        OperationHistoryPort history = mock(OperationHistoryPort.class);
        BatchMetadataEditUseCase batch = mock(BatchMetadataEditUseCase.class);
        MergeBooksUseCase merge = mock(MergeBooksUseCase.class);
        when(history.latestUndoable()).thenReturn(Optional.of(entry("newer", LibraryOperationHistoryType.BULK_METADATA)));
        var useCase = new LibraryOperationHistoryUseCase(history, batch, merge, new LibraryOperationCoordinator());

        assertThatThrownBy(() -> useCase.undo("c1", "older", new AtomicBoolean(), ignored -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("newer reversible");

        verifyNoInteractions(batch, merge);
    }

    @Test
    void latestBulkUndoDelegatesToRestartSafeBatchUndo() {
        OperationHistoryPort history = mock(OperationHistoryPort.class);
        BatchMetadataEditUseCase batch = mock(BatchMetadataEditUseCase.class);
        MergeBooksUseCase merge = mock(MergeBooksUseCase.class);
        when(history.latestUndoable()).thenReturn(Optional.of(entry("bulk-1", LibraryOperationHistoryType.BULK_METADATA)));
        when(batch.undo(eq("c1"), eq("bulk-1"), any(), any()))
                .thenReturn(new BatchMetadataEditResult("bulk-1", 3, 3, true));
        var useCase = new LibraryOperationHistoryUseCase(history, batch, merge, new LibraryOperationCoordinator());

        LibraryOperationUndoResult result = useCase.undoLatest("c1", new AtomicBoolean(), ignored -> { });

        assertThat(result.operationId()).isEqualTo("bulk-1");
        assertThat(result.type()).isEqualTo(LibraryOperationHistoryType.BULK_METADATA);
        assertThat(result.affectedCount()).isEqualTo(3);
        assertThat(result.searchIndexSynchronizedOrScheduled()).isTrue();
        verify(batch).undo(eq("c1"), eq("bulk-1"), any(), any());
        verifyNoInteractions(merge);
    }

    @Test
    void mergeUndoPropagatesSearchIndexSynchronizationStatus() {
        OperationHistoryPort history = mock(OperationHistoryPort.class);
        BatchMetadataEditUseCase batch = mock(BatchMetadataEditUseCase.class);
        MergeBooksUseCase merge = mock(MergeBooksUseCase.class);
        when(history.latestUndoable()).thenReturn(Optional.of(entry("merge-1", LibraryOperationHistoryType.BOOK_MERGE)));
        when(merge.undo("merge-1")).thenReturn(new BookMergeResult(
                "merge-1", BookId.fromString("00000000-0000-0000-0000-000000000001"),
                BookId.fromString("00000000-0000-0000-0000-000000000002"), Instant.now(), true, true));
        var useCase = new LibraryOperationHistoryUseCase(history, batch, merge, new LibraryOperationCoordinator());

        LibraryOperationUndoResult result = useCase.undo("c1", "merge-1", new AtomicBoolean(), ignored -> { });

        assertThat(result.operationId()).isEqualTo("merge-1");
        assertThat(result.type()).isEqualTo(LibraryOperationHistoryType.BOOK_MERGE);
        assertThat(result.affectedCount()).isEqualTo(2);
        assertThat(result.searchIndexSynchronizedOrScheduled()).isTrue();
        verify(merge).undo("merge-1");
        verifyNoInteractions(batch);
    }

    private static LibraryOperationHistoryEntry entry(String id, LibraryOperationHistoryType type) {
        Instant now = Instant.parse("2026-09-11T12:00:00Z");
        return new LibraryOperationHistoryEntry(id, type, id, 2, 2, now, now, null);
    }
}
