package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReaderBookmarkCoordinatorTest {

    @Test
    void lifecycleConflictIsDroppedInsteadOfSurfacingAsPersistenceFailure() {
        NewReaderPersistenceService persistence = mock(NewReaderPersistenceService.class);
        UiBackgroundExecutor executor = mock(UiBackgroundExecutor.class);
        ReaderBookmarkCoordinator coordinator = new ReaderBookmarkCoordinator(persistence, executor);
        when(executor.<List<ReaderBookmarkItem>>submitCollectionScoped(
                eq(LibraryOperationType.READER_STATE), org.mockito.ArgumentMatchers.<Callable<List<ReaderBookmarkItem>>>any()))
                .thenReturn(CompletableFuture.failedFuture(new LibraryOperationConflictException(
                        LibraryOperationType.SWITCH, LibraryOperationType.READER_STATE)));
        AtomicBoolean failed = new AtomicBoolean();

        coordinator.load(currentContext(), ignored -> { }, error -> failed.set(true));

        assertThat(failed).isFalse();
    }

    @Test
    void staleCompletionNeverReachesUiCallback() {
        NewReaderPersistenceService persistence = mock(NewReaderPersistenceService.class);
        UiBackgroundExecutor executor = mock(UiBackgroundExecutor.class);
        ReaderBookmarkCoordinator coordinator = new ReaderBookmarkCoordinator(persistence, executor);
        CompletableFuture<List<ReaderBookmarkItem>> pending = new CompletableFuture<>();
        when(executor.<List<ReaderBookmarkItem>>submitCollectionScoped(
                eq(LibraryOperationType.READER_STATE), org.mockito.ArgumentMatchers.<Callable<List<ReaderBookmarkItem>>>any()))
                .thenReturn(pending);
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicBoolean delivered = new AtomicBoolean();
        ReaderBookmarkCoordinator.Context context = new ReaderBookmarkCoordinator.Context(
                "collection", "book", current::get);

        coordinator.load(context, ignored -> delivered.set(true), error -> { });
        current.set(false);
        pending.complete(List.of());

        assertThat(delivered).isFalse();
    }

    private static ReaderBookmarkCoordinator.Context currentContext() {
        return new ReaderBookmarkCoordinator.Context("collection", "book", () -> true);
    }
}
