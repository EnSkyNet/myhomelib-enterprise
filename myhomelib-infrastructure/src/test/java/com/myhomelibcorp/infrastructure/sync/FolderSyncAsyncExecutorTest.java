package com.myhomelibcorp.infrastructure.sync;

import com.myhomelibcorp.application.imports.scanner.LibraryScanner;
import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.sync.SyncOptions;
import com.myhomelibcorp.infrastructure.importengine.InpxImportPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FolderSyncAsyncExecutorTest {

    @TempDir Path temp;

    @Test
    void asyncSyncRunsOnInjectedIoExecutorNotCallerThread() throws Exception {
        BookQueryRepository queries = mock(BookQueryRepository.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        SearchIndexSynchronizer synchronizer = mock(SearchIndexSynchronizer.class);
        LibraryScanner scanner = mock(LibraryScanner.class);
        ImporterRegistry registry = mock(ImporterRegistry.class);
        InpxImportPipeline inpx = mock(InpxImportPipeline.class);
        AtomicReference<String> scanThread = new AtomicReference<>();
        when(scanner.streamSupportedFiles(any(Path.class), anyBoolean(), anyInt(), anyLong())).thenAnswer(invocation -> {
            scanThread.set(Thread.currentThread().getName());
            return Stream.empty();
        });

        ExecutorService io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-io-sync");
            t.setDaemon(true);
            return t;
        });
        try {
            FolderSyncService service = new FolderSyncService(
                    queries, mutations, synchronizer, scanner, registry, inpx, io);
            var result = service.syncFolderAsync(temp, SyncOptions.builder().build()).get(5, TimeUnit.SECONDS);
            assertThat(result.getErrors()).isZero();
            assertThat(scanThread.get()).isEqualTo("test-io-sync");
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void cancellingFuturePropagatesCooperativeCancelFlag() throws Exception {
        BookQueryRepository queries = mock(BookQueryRepository.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        SearchIndexSynchronizer synchronizer = mock(SearchIndexSynchronizer.class);
        LibraryScanner scanner = mock(LibraryScanner.class);
        ImporterRegistry registry = mock(ImporterRegistry.class);
        InpxImportPipeline inpx = mock(InpxImportPipeline.class);
        CountDownLatch scanning = new CountDownLatch(1);
        when(scanner.streamSupportedFiles(any(Path.class), anyBoolean(), anyInt(), anyLong())).thenAnswer(invocation -> {
            scanning.countDown();
            return Stream.generate(() -> temp.resolve("missing.fb2")).limit(100_000);
        });

        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            FolderSyncService service = new FolderSyncService(
                    queries, mutations, synchronizer, scanner, registry, inpx, io);
            CompletableFuture<?> future = service.syncFolderAsync(temp, SyncOptions.builder().build());
            assertThat(scanning.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(future.cancel(true)).isTrue();
            assertThat(future.isCancelled()).isTrue();
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void cancellingQueuedSyncDoesNotCancelDifferentRunningSync() throws Exception {
        BookQueryRepository queries = mock(BookQueryRepository.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        SearchIndexSynchronizer synchronizer = mock(SearchIndexSynchronizer.class);
        LibraryScanner scanner = mock(LibraryScanner.class);
        ImporterRegistry registry = mock(ImporterRegistry.class);
        InpxImportPipeline inpx = mock(InpxImportPipeline.class);
        BookImporterPort importer = mock(BookImporterPort.class);
        Path book = temp.resolve("queued-cancellation.fb2");
        CountDownLatch scanning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(scanner.streamSupportedFiles(any(Path.class), anyBoolean(), anyInt(), anyLong())).thenAnswer(invocation -> {
            scanning.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Scanner timeout");
            return Stream.of(book);
        });
        when(registry.findImporter(book)).thenReturn(importer);
        when(importer.importBooks(book)).thenReturn(Stream.empty());

        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            FolderSyncService service = new FolderSyncService(
                    queries, mutations, synchronizer, scanner, registry, inpx, io);
            CompletableFuture<?> running = service.syncFolderAsync(temp, SyncOptions.builder().build());
            assertThat(scanning.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<?> queued = service.syncFolderAsync(temp, SyncOptions.builder().build());
            assertThat(queued.cancel(true)).isTrue();
            release.countDown();
            var result = (com.myhomelibcorp.domain.model.sync.SyncResult) running.get(5, TimeUnit.SECONDS);
            assertThat(result.getErrors()).isZero();
            verify(registry).findImporter(book);
            assertThat(queued.isCancelled()).isTrue();
        } finally {
            release.countDown();
            io.shutdownNow();
        }
    }

    @Test
    void lazyDirectoryTraversalErrorIsReportedInSyncResult() throws Exception {
        BookQueryRepository queries = mock(BookQueryRepository.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        SearchIndexSynchronizer synchronizer = mock(SearchIndexSynchronizer.class);
        LibraryScanner scanner = mock(LibraryScanner.class);
        ImporterRegistry registry = mock(ImporterRegistry.class);
        InpxImportPipeline inpx = mock(InpxImportPipeline.class);
        when(scanner.streamSupportedFiles(any(Path.class), anyBoolean(), anyInt(), anyLong())).thenReturn(
                Stream.<Path>generate(() -> {
                    throw new java.io.UncheckedIOException(new java.io.IOException("access denied during walk"));
                }).limit(1));
        FolderSyncService service = new FolderSyncService(
                queries, mutations, synchronizer, scanner, registry, inpx, Runnable::run);

        var result = service.syncFolder(temp, SyncOptions.builder().build());
        assertThat(result.getErrors()).isEqualTo(1);
        assertThat(result.getErrorMessages()).anyMatch(msg -> msg.contains("access denied"));
        assertThat(service.isSyncing()).isFalse();
    }

    @Test
    void rejectedIoAdmissionReturnsFailedFuture() {
        BookQueryRepository queries = mock(BookQueryRepository.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        SearchIndexSynchronizer synchronizer = mock(SearchIndexSynchronizer.class);
        LibraryScanner scanner = mock(LibraryScanner.class);
        ImporterRegistry registry = mock(ImporterRegistry.class);
        InpxImportPipeline inpx = mock(InpxImportPipeline.class);

        FolderSyncService service = new FolderSyncService(
                queries, mutations, synchronizer, scanner, registry, inpx,
                command -> { throw new RejectedExecutionException("io full"); });

        CompletableFuture<?> future = service.syncFolderAsync(temp, SyncOptions.builder().build());
        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::join)
                .hasCauseInstanceOf(RejectedExecutionException.class)
                .hasRootCauseMessage("io full");
    }
}
