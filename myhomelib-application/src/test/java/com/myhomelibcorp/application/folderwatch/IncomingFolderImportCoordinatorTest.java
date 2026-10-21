package com.myhomelibcorp.application.folderwatch;

import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.context.StaleImportSourceException;
import com.myhomelibcorp.application.port.out.collection.IncomingFolderWatchPort;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class IncomingFolderImportCoordinatorTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void executorRejectionDoesNotPermanentlyBlockIncomingCollection() {
        IncomingFolderWatchPort watcher = mock(IncomingFolderWatchPort.class);
        ImportFileUseCase importer = mock(ImportFileUseCase.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        when(collections.getCurrentCollection()).thenReturn(collection("target"));
        var submitCount = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorPort rejectOnce = new ExecutorPort() {
            @Override public <T> CompletableFuture<T> submit(Callable<T> task) {
                throw new UnsupportedOperationException("not used");
            }
            @Override public void execute(Runnable task) {
                if (submitCount.getAndIncrement() == 0) {
                    throw new java.util.concurrent.RejectedExecutionException("simulated full queue");
                }
                task.run();
            }
        };
        IncomingFolderImportCoordinator coordinator = new IncomingFolderImportCoordinator(
                watcher, importer, collections, rejectOnce);

        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> coordinator.schedule("target"));
        coordinator.schedule("target");

        verify(watcher).listReady("target", 25);
        org.junit.jupiter.api.Assertions.assertEquals(2, submitCount.get());
    }

    @Test
    void readyNotificationDuringLastEmptyQueryTriggersAnotherDrain() throws Exception {
        IncomingFolderWatchPort watcher = mock(IncomingFolderWatchPort.class);
        ImportFileUseCase importer = mock(ImportFileUseCase.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        IncomingFolderImportCoordinator coordinator = new IncomingFolderImportCoordinator(
                watcher, importer, collections, new DirectExecutor());
        Path folder = Path.of("/tmp/incoming");
        Path file = folder.resolve("late.txt");
        IncomingFolderCandidate late = new IncomingFolderCandidate(
                "target", file, HASH, 10, 20, IncomingFolderCandidateStatus.READY, Instant.now(), null);
        when(collections.getCurrentCollection()).thenReturn(collection("target"));
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        when(watcher.listReady("target", 25)).thenAnswer(invocation -> {
            int pass = reads.getAndIncrement();
            if (pass == 0) {
                // Event occurs while the original draining worker is still registered.
                coordinator.schedule("target");
                return List.of();
            }
            return pass == 1 ? List.of(late) : List.of();
        });
        when(watcher.claimReady("target", file, HASH)).thenReturn(true);
        when(watcher.findState("target")).thenReturn(Optional.of(new IncomingFolderWatchState(
                "target", folder, true, 2, 3, Instant.now(), "WATCHING", 0, 1, 0, 0, 0, 0)));
        when(importer.execute(any())).thenReturn(new ImportResult(1, 0, 0, 0, 1));

        coordinator.schedule("target");

        verify(watcher).markImported("target", file, HASH, 1, 0, 0);
        org.junit.jupiter.api.Assertions.assertTrue(reads.get() >= 3);
    }

    @Test
    void inactiveCollectionRemainsQueuedAndActiveCollectionImportsInBackground() throws Exception {
        IncomingFolderWatchPort watcher = mock(IncomingFolderWatchPort.class);
        ImportFileUseCase importer = mock(ImportFileUseCase.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        ExecutorPort direct = new DirectExecutor();
        IncomingFolderImportCoordinator coordinator = new IncomingFolderImportCoordinator(watcher, importer, collections, direct);
        Path folder = Path.of("/tmp/incoming");
        Path file = folder.resolve("book.txt");
        IncomingFolderCandidate candidate = new IncomingFolderCandidate(
                "target", file, HASH, 10, 20, IncomingFolderCandidateStatus.READY, Instant.now(), null);

        when(collections.getCurrentCollection()).thenReturn(collection("other"));
        coordinator.schedule("target");
        verifyNoInteractions(importer);
        verify(watcher, never()).claimReady(any(), any(), any());

        when(collections.getCurrentCollection()).thenReturn(collection("target"));
        when(watcher.listReady("target", 25)).thenReturn(List.of(candidate), List.of());
        when(watcher.claimReady("target", file, HASH)).thenReturn(true);
        when(watcher.findState("target")).thenReturn(Optional.of(new IncomingFolderWatchState(
                "target", folder, true, 2, 3, Instant.now(), "WATCHING", 0, 1, 0, 0, 0, 0)));
        when(importer.execute(any())).thenReturn(new ImportResult(1, 0, 0, 0, 1));

        coordinator.schedule("target");

        verify(importer, times(1)).execute(argThat(context ->
                HASH.equals(context.getExpectedSourceSha256())
                        && "target".equals(context.getExpectedCollectionId())
                        && file.equals(context.getFile())));
        verify(watcher).markImported("target", file, HASH, 1, 0, 0);
    }


    @Test
    void lifecycleChangeReleasesClaimForRetryInsteadOfMarkingCandidateFailed() {
        IncomingFolderWatchPort watcher = mock(IncomingFolderWatchPort.class);
        ImportFileUseCase importer = mock(ImportFileUseCase.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        IncomingFolderImportCoordinator coordinator = new IncomingFolderImportCoordinator(
                watcher, importer, collections, new DirectExecutor());
        Path folder = Path.of("/tmp/incoming");
        Path file = folder.resolve("book.txt");
        IncomingFolderCandidate candidate = new IncomingFolderCandidate(
                "target", file, HASH, 10, 20, IncomingFolderCandidateStatus.READY, Instant.now(), null);

        when(collections.getCurrentCollection()).thenReturn(collection("target"));
        when(watcher.listReady("target", 25)).thenReturn(List.of(candidate));
        when(watcher.claimReady("target", file, HASH)).thenReturn(true);
        when(watcher.findState("target")).thenReturn(Optional.of(new IncomingFolderWatchState(
                "target", folder, true, 2, 3, Instant.now(), "WATCHING", 0, 1, 0, 0, 0, 0)));
        when(importer.execute(any())).thenThrow(
                new ImportFileUseCase.StaleImportCollectionException("collection switched"));

        coordinator.schedule("target");

        verify(watcher).releaseClaim("target", file, HASH);
        verify(watcher, never()).markFailed(eq("target"), eq(file), eq(HASH), any());
    }

    @Test
    void changedSourceIsFailedAndRescannedInsteadOfImported() {
        IncomingFolderWatchPort watcher = mock(IncomingFolderWatchPort.class);
        ImportFileUseCase importer = mock(ImportFileUseCase.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        IncomingFolderImportCoordinator coordinator = new IncomingFolderImportCoordinator(
                watcher, importer, collections, new DirectExecutor());
        Path folder = Path.of("/tmp/incoming");
        Path file = folder.resolve("book.txt");
        IncomingFolderCandidate candidate = new IncomingFolderCandidate(
                "target", file, HASH, 10, 20, IncomingFolderCandidateStatus.READY, Instant.now(), null);

        when(collections.getCurrentCollection()).thenReturn(collection("target"));
        when(watcher.listReady("target", 25)).thenReturn(List.of(candidate), List.of());
        when(watcher.claimReady("target", file, HASH)).thenReturn(true);
        when(watcher.findState("target")).thenReturn(Optional.of(new IncomingFolderWatchState(
                "target", folder, true, 2, 3, Instant.now(), "WATCHING", 0, 1, 0, 0, 0, 0)));
        when(importer.execute(any())).thenThrow(
                new StaleImportSourceException("source changed"));

        coordinator.schedule("target");

        verify(watcher).markFailed(eq("target"), eq(file), eq(HASH), contains("source changed"));
        verify(watcher).scanNow("target");
        verify(watcher, never()).markImported(any(), any(), any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void cyclicUnclassifiedImportFailureIsReportedWithoutHanging() {
        IncomingFolderWatchPort watcher = mock(IncomingFolderWatchPort.class);
        ImportFileUseCase importer = mock(ImportFileUseCase.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        IncomingFolderImportCoordinator coordinator = new IncomingFolderImportCoordinator(
                watcher, importer, collections, new DirectExecutor());
        Path file = Path.of("/tmp/incoming/cycle.txt");
        IncomingFolderCandidate candidate = new IncomingFolderCandidate(
                "target", file, HASH, 10, 20, IncomingFolderCandidateStatus.READY, Instant.now(), null);
        when(collections.getCurrentCollection()).thenReturn(collection("target"));
        when(watcher.listReady("target", 25)).thenReturn(List.of(candidate), List.of());
        when(watcher.claimReady("target", file, HASH)).thenReturn(true);
        IllegalStateException first = new IllegalStateException("outside");
        IllegalStateException second = new IllegalStateException("nested cycle");
        first.initCause(second);
        second.initCause(first);
        when(importer.execute(any())).thenThrow(first);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
                () -> coordinator.schedule("target"));

        verify(watcher).markFailed(eq("target"), eq(file), eq(HASH), contains("nested cycle"));
    }

    @Test
    void cyclicStaleSourceCauseStillTriggersRescan() {
        IncomingFolderWatchPort watcher = mock(IncomingFolderWatchPort.class);
        ImportFileUseCase importer = mock(ImportFileUseCase.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        IncomingFolderImportCoordinator coordinator = new IncomingFolderImportCoordinator(
                watcher, importer, collections, new DirectExecutor());
        Path file = Path.of("/tmp/incoming/stale.txt");
        IncomingFolderCandidate candidate = new IncomingFolderCandidate(
                "target", file, HASH, 10, 20, IncomingFolderCandidateStatus.READY, Instant.now(), null);
        when(collections.getCurrentCollection()).thenReturn(collection("target"));
        when(watcher.listReady("target", 25)).thenReturn(List.of(candidate), List.of());
        when(watcher.claimReady("target", file, HASH)).thenReturn(true);
        IllegalStateException outer = new IllegalStateException("wrapper");
        StaleImportSourceException stale = new StaleImportSourceException("source changed during read");
        outer.initCause(stale);
        stale.initCause(outer);
        when(importer.execute(any())).thenThrow(outer);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
                () -> coordinator.schedule("target"));

        verify(watcher).markFailed(eq("target"), eq(file), eq(HASH), any());
        verify(watcher).scanNow("target");
        verify(watcher, never()).markImported(any(), any(), any(), anyLong(), anyLong(), anyLong());
    }

    private static Collection collection(String id) {
        return new Collection(id, id, Path.of("/tmp/" + id), id + ".db", 0, null, null, null, null);
    }

    private static final class DirectExecutor implements ExecutorPort {
        @Override public <T> CompletableFuture<T> submit(Callable<T> task) {
            try { return CompletableFuture.completedFuture(task.call()); }
            catch (Exception e) { return CompletableFuture.failedFuture(e); }
        }
        @Override public void execute(Runnable task) { task.run(); }
    }
}
