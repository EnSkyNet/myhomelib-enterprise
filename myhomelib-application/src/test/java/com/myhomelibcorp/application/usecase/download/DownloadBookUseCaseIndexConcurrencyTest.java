package com.myhomelibcorp.application.usecase.download;

import com.myhomelibcorp.application.content.indexing.ContentIndexAutoSchedulingService;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.catalog.CatalogUpdateTrackingPort;
import com.myhomelibcorp.application.port.out.download.DownloadQueuePort;
import com.myhomelibcorp.application.port.out.download.OnlineBookDownloadPort;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DownloadBookUseCaseIndexConcurrencyTest {

    @Test
    void downloadDuringBackgroundRebuildReturnsWithoutWaitingAndDefersSelectiveLuceneSync() throws Exception {
        OnlineBookDownloadPort downloadPort = mock(OnlineBookDownloadPort.class);
        DownloadQueuePort queue = mock(DownloadQueuePort.class);
        BookCommandRepository commands = mock(BookCommandRepository.class);
        CatalogUpdateTrackingPort catalogTracking = mock(CatalogUpdateTrackingPort.class);
        StatisticsRepository statistics = mock(StatisticsRepository.class);
        SearchIndexSynchronizer search = mock(SearchIndexSynchronizer.class);
        ContentIndexAutoSchedulingService content = mock(ContentIndexAutoSchedulingService.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        LibraryOperationCoordinator operations = new LibraryOperationCoordinator();
        CapturingExecutor executor = new CapturingExecutor();

        DownloadBookUseCase useCase = new DownloadBookUseCase(downloadPort, queue, commands,
                catalogTracking, statistics, search, content, lifecycle, operations, executor);

        Collection collection = new Collection("collection-1", "Online", Path.of("/library"),
                "library.db", 2, null, null, "https://example.test", null);
        BookDto book = mock(BookDto.class);
        when(book.getId()).thenReturn("00000000-0000-0000-0000-000000000001");
        when(book.getFolder()).thenReturn("");
        when(book.getFileName()).thenReturn("book.fb2");
        when(book.getArchiveEntry()).thenReturn("");
        when(lifecycle.getCurrentCollection()).thenReturn(collection);

        Path physical = Path.of("/library/book.fb2");
        when(downloadPort.download(eq(book), eq(collection), any(AtomicBoolean.class), any(), eq(false)))
                .thenReturn(new OnlineBookDownloadPort.DownloadedBook(
                        Path.of("/library"), "", "book.fb2", "", physical));
        when(search.synchronizeSafelyNow(anyList())).thenReturn(true);
        when(content.schedule(eq("collection-1"), any()))
                .thenReturn(new ContentIndexAutoSchedulingService.SchedulingResult(true, false, 1, 1, ""));

        var indexLease = operations.acquireDetachedShared(LibraryOperationType.INDEX);
        Path actual = useCase.execute(book, collection, new AtomicBoolean(false), ignored -> { });

        assertEquals(physical, actual);
        verify(search, never()).synchronizeSafelyNow(anyList());
        verify(content, never()).schedule(eq("collection-1"), any());
        Runnable deferred = executor.task.get();
        assertNotNull(deferred, "Derived-index synchronization must be deferred while full INDEX rebuild owns the barrier");

        indexLease.close();
        assertEquals(LibraryOperationType.INDEX, operations.activeOperation(),
                "deferred sync lease must keep the collection barrier alive after rebuild releases its own lease");
        assertThrows(com.myhomelibcorp.application.operation.LibraryOperationConflictException.class,
                () -> operations.acquire(LibraryOperationType.SWITCH));

        deferred.run();
        verify(search).synchronizeSafelyNow(anyList());
        verify(content).schedule(eq("collection-1"), any());
        assertFalse(operations.isBusy());
    }


    @Test
    void downloadDetectsIndexLeaseEvenWhenQueryOwnsSharedRoot() throws Exception {
        OnlineBookDownloadPort downloadPort = mock(OnlineBookDownloadPort.class);
        DownloadQueuePort queue = mock(DownloadQueuePort.class);
        BookCommandRepository commands = mock(BookCommandRepository.class);
        CatalogUpdateTrackingPort catalogTracking = mock(CatalogUpdateTrackingPort.class);
        StatisticsRepository statistics = mock(StatisticsRepository.class);
        SearchIndexSynchronizer search = mock(SearchIndexSynchronizer.class);
        ContentIndexAutoSchedulingService content = mock(ContentIndexAutoSchedulingService.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        LibraryOperationCoordinator operations = new LibraryOperationCoordinator();
        CapturingExecutor executor = new CapturingExecutor();

        DownloadBookUseCase useCase = new DownloadBookUseCase(downloadPort, queue, commands,
                catalogTracking, statistics, search, content, lifecycle, operations, executor);

        Collection collection = new Collection("collection-1", "Online", Path.of("/library"),
                "library.db", 2, null, null, "https://example.test", null);
        BookDto book = mock(BookDto.class);
        when(book.getId()).thenReturn("00000000-0000-0000-0000-000000000002");
        when(book.getFolder()).thenReturn("");
        when(book.getFileName()).thenReturn("book2.fb2");
        when(book.getArchiveEntry()).thenReturn("");
        when(lifecycle.getCurrentCollection()).thenReturn(collection);

        Path physical = Path.of("/library/book2.fb2");
        when(downloadPort.download(eq(book), eq(collection), any(AtomicBoolean.class), any(), eq(false)))
                .thenReturn(new OnlineBookDownloadPort.DownloadedBook(
                        Path.of("/library"), "", "book2.fb2", "", physical));
        when(search.synchronizeSafelyNow(anyList())).thenReturn(true);
        when(content.schedule(eq("collection-1"), any()))
                .thenReturn(new ContentIndexAutoSchedulingService.SchedulingResult(true, false, 1, 1, ""));

        var queryLease = operations.acquireDetachedShared(LibraryOperationType.QUERY);
        var indexLease = operations.acquireDetachedShared(LibraryOperationType.INDEX);
        assertEquals(LibraryOperationType.QUERY, operations.activeOperation());
        assertTrue(operations.isOperationActive(LibraryOperationType.INDEX));

        Path actual = useCase.execute(book, collection, new AtomicBoolean(false), ignored -> { });
        assertEquals(physical, actual);
        verify(search, never()).synchronizeSafelyNow(anyList());
        verify(content, never()).schedule(eq("collection-1"), any());
        Runnable deferred = executor.task.get();
        assertNotNull(deferred, "joined INDEX lease must still defer post-download Lucene synchronization");

        indexLease.close();
        queryLease.close();
        assertTrue(operations.isBusy(), "deferred DOWNLOAD lease must protect the collection until repair finishes");
        deferred.run();
        verify(search).synchronizeSafelyNow(anyList());
        verify(content).schedule(eq("collection-1"), any());
        assertFalse(operations.isBusy());
    }

    private static final class CapturingExecutor implements ExecutorPort {
        private final AtomicReference<Runnable> task = new AtomicReference<>();

        @Override
        public <T> CompletableFuture<T> submit(Callable<T> task) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not used"));
        }

        @Override
        public void execute(Runnable task) {
            if (!this.task.compareAndSet(null, task)) throw new IllegalStateException("unexpected second task");
        }
    }
}
