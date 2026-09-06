package com.myhomelibcorp.infrastructure.sync;

import com.myhomelibcorp.application.imports.scanner.LibraryScanner;
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
