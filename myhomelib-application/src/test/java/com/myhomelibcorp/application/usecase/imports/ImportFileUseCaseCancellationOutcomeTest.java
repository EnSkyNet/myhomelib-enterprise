package com.myhomelibcorp.application.usecase.imports;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.error.ImportErrorHandler;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationOutcome;
import com.myhomelibcorp.application.port.out.catalog.CatalogImportPort;
import com.myhomelibcorp.application.port.out.event.EventPublisher;
import com.myhomelibcorp.application.port.out.importer.FastImportService;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportFileUseCaseCancellationOutcomeTest {

    @Test
    void cancelledFastImportReportsCancelledLibraryOperationOutcome() {
        FastImportService fastImport = mock(FastImportService.class);
        LibraryOperationCoordinator operations = new LibraryOperationCoordinator();
        AtomicReference<LibraryOperationOutcome> outcome = new AtomicReference<>();
        operations.addCompletionListener(completion -> outcome.set(completion.outcome()));
        when(fastImport.importInpx(any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(ImportResult.cancelled(1));

        ImportFileUseCase useCase = new ImportFileUseCase(
                mock(ImporterRegistry.class),
                mock(BookSaver.class),
                mock(ImportErrorHandler.class),
                mock(EventPublisher.class),
                mock(BulkImportOptimizer.class),
                fastImport,
                mock(SearchIndexer.class),
                mock(CatalogImportPort.class),
                mock(BookQueryRepository.class),
                operations);

        ImportResult result = useCase.execute(ImportContext.builder()
                .file(Path.of("cancelled.inpx"))
                .batchSize(1000)
                .catalogFullSnapshot(true)
                .build());

        assertEquals(com.myhomelibcorp.application.imports.statistics.ImportStatus.CANCELLED, result.status());
        assertEquals(LibraryOperationOutcome.CANCELLED, outcome.get());
        assertFalse(operations.isBusy());
    }
    @Test
    void fatalFastImportFailureReportsFailedLibraryOperationOutcome() {
        FastImportService fastImport = mock(FastImportService.class);
        LibraryOperationCoordinator operations = new LibraryOperationCoordinator();
        AtomicReference<LibraryOperationOutcome> outcome = new AtomicReference<>();
        operations.addCompletionListener(completion -> outcome.set(completion.outcome()));
        when(fastImport.importInpx(any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("broken import"));

        ImportFileUseCase useCase = new ImportFileUseCase(
                mock(ImporterRegistry.class),
                mock(BookSaver.class),
                mock(ImportErrorHandler.class),
                mock(EventPublisher.class),
                mock(BulkImportOptimizer.class),
                fastImport,
                mock(SearchIndexer.class),
                mock(CatalogImportPort.class),
                mock(BookQueryRepository.class),
                operations);

        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                useCase.execute(ImportContext.builder()
                        .file(Path.of("broken.inpx"))
                        .batchSize(1000)
                        .catalogFullSnapshot(true)
                        .build()));

        assertEquals("broken import", failure.getMessage());
        assertEquals(LibraryOperationOutcome.FAILED, outcome.get());
        assertFalse(operations.isBusy());
    }

}
