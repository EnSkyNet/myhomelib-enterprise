package com.myhomelibcorp.application.folderwatch;

import com.myhomelibcorp.application.imports.statistics.ImportResult;
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
import static org.mockito.Mockito.*;

class IncomingFolderImportCoordinatorTest {
    private static final String HASH = "a".repeat(64);

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

        verify(importer, times(1)).execute(any());
        verify(watcher).markImported("target", file, HASH, 1, 0, 0);
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
