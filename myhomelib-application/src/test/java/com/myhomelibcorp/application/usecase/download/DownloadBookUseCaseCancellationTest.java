package com.myhomelibcorp.application.usecase.download;

import com.myhomelibcorp.application.content.indexing.ContentIndexAutoSchedulingService;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationOutcome;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DownloadBookUseCaseCancellationTest {

    @Test
    void cancelledDownloadReportsCancelledLibraryOperationOutcome() throws Exception {
        OnlineBookDownloadPort downloadPort = mock(OnlineBookDownloadPort.class);
        DownloadQueuePort queue = mock(DownloadQueuePort.class);
        BookCommandRepository commands = mock(BookCommandRepository.class);
        CatalogUpdateTrackingPort catalogTracking = mock(CatalogUpdateTrackingPort.class);
        StatisticsRepository statistics = mock(StatisticsRepository.class);
        SearchIndexSynchronizer search = mock(SearchIndexSynchronizer.class);
        ContentIndexAutoSchedulingService content = mock(ContentIndexAutoSchedulingService.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        ExecutorPort executor = mock(ExecutorPort.class);
        LibraryOperationCoordinator operations = new LibraryOperationCoordinator();
        AtomicReference<LibraryOperationOutcome> outcome = new AtomicReference<>();
        operations.addCompletionListener(completion -> outcome.set(completion.outcome()));

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
        when(downloadPort.download(eq(book), eq(collection), any(AtomicBoolean.class), any(), eq(false)))
                .thenThrow(new CancellationException("cancelled by user"));

        AtomicBoolean cancel = new AtomicBoolean(true);
        assertThrows(CancellationException.class,
                () -> useCase.execute(book, collection, cancel, ignored -> { }));

        assertEquals(LibraryOperationOutcome.CANCELLED, outcome.get());
        assertFalse(operations.isBusy());
        verify(queue).markCancelled(eq("collection-1"),
                eq("00000000-0000-0000-0000-000000000001"), any());
        verify(queue, never()).markFailed(anyString(), anyString(), anyString(), any());
    }
}
