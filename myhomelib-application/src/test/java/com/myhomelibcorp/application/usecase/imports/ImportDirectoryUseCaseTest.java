package com.myhomelibcorp.application.usecase.imports;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationOutcome;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.diagnostics.ImportIssue;
import com.myhomelibcorp.application.imports.diagnostics.ImportSeverity;
import com.myhomelibcorp.application.imports.scanner.LibraryScanner;
import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.port.out.event.EventPublisher;
import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseInitializerPort;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ImportDirectoryUseCaseTest {

    @Test
    void streamsDirectoryOnceAndDoesNotForwardAggregateProgressToChildImports() throws Exception {
        Fixture f = new Fixture();
        Path root = Path.of("library");
        Path first = root.resolve("a.fb2");
        Path second = root.resolve("b.fb2");
        when(f.scanner.streamSupportedFiles(root)).thenReturn(java.util.stream.Stream.of(first, second));
        when(f.importFile.execute(any())).thenReturn(success(0, ImportChangeSet.empty(true)));
        List<Double> progress = new ArrayList<>();

        ImportResult result = f.useCase.execute(ImportContext.builder()
                .rootDirectory(root)
                .indexAfterSave(false)
                .progressListener(progress::add)
                .statusConsumer(ignored -> { })
                .batchSize(1000)
                .build());

        assertThat(result.status()).isEqualTo(ImportStatus.SUCCESS);
        assertThat(progress).containsExactly(-1.0, 1.0);
        verify(f.scanner, times(1)).streamSupportedFiles(root);

        ArgumentCaptor<ImportContext> child = ArgumentCaptor.forClass(ImportContext.class);
        verify(f.importFile, times(2)).execute(child.capture());
        assertThat(child.getAllValues()).allSatisfy(context -> {
            assertThat(context.getProgressListener()).isNull();
            assertThat(context.getStatusConsumer()).isNull();
            assertThat(context.getOperationProgressListener()).isNull();
            assertThat(context.isIndexAfterSave()).isFalse();
            assertThat(context.isPublishFinishedEvent()).isFalse();
            assertThat(context.getRootDirectory()).isEqualTo(root);
        });
    }

    @Test
    void cancelledDirectorySynchronizesCommittedChangesButNeverReportsHundredPercent() throws Exception {
        Fixture f = new Fixture();
        Path root = Path.of("library");
        Path first = root.resolve("a.fb2");
        Path second = root.resolve("b.fb2");
        AtomicBoolean cancel = new AtomicBoolean(false);
        BookId id = BookId.generate();
        when(f.scanner.streamSupportedFiles(root)).thenReturn(java.util.stream.Stream.of(first, second));
        when(f.importFile.execute(any())).thenAnswer(invocation -> {
            cancel.set(true);
            return success(1, new ImportChangeSet(Set.of(id.asString()), Set.of(), Set.of(), true));
        });
        when(f.searchSync.synchronizeSafelyNow(any())).thenReturn(true);
        List<Double> progress = new ArrayList<>();
        List<String> status = new ArrayList<>();

        ImportResult result = f.useCase.execute(ImportContext.builder()
                .rootDirectory(root)
                .indexAfterSave(true)
                .progressListener(progress::add)
                .statusConsumer(status::add)
                .cancelFlag(cancel)
                .batchSize(1000)
                .build());

        assertThat(result.status()).isEqualTo(ImportStatus.CANCELLED);
        assertThat(progress).doesNotContain(1.0);
        assertThat(status).endsWith("Імпорт скасовано");
        verify(f.importFile, times(1)).execute(any());
        verify(f.searchSync).synchronizeSafelyNow(List.of(id));
    }

    @Test
    void childCancelledStatusStopsDirectoryEvenIfExternalFlagWasNotSet() throws Exception {
        Fixture f = new Fixture();
        Path root = Path.of("library");
        when(f.scanner.streamSupportedFiles(root)).thenReturn(java.util.stream.Stream.of(
                root.resolve("a.fb2"), root.resolve("b.fb2")));
        when(f.importFile.execute(any())).thenReturn(new ImportResult(
                0, 0, 0, 0, 1, ImportStatus.CANCELLED, ImportChangeSet.empty(false), List.of()));
        List<Double> progress = new ArrayList<>();

        ImportResult result = f.useCase.execute(ImportContext.builder()
                .rootDirectory(root)
                .indexAfterSave(true)
                .progressListener(progress::add)
                .batchSize(1000)
                .build());

        assertThat(result.status()).isEqualTo(ImportStatus.CANCELLED);
        assertThat(progress).doesNotContain(1.0);
        verify(f.importFile, times(1)).execute(any());
        verifyNoInteractions(f.searchSync, f.indexRebuilder);
    }

    @Test
    void cancellationBeforeFirstChildReportsCancelledLibraryOperationOutcome() throws Exception {
        Fixture f = new Fixture();
        Path root = Path.of("library");
        AtomicReference<LibraryOperationOutcome> outcome = new AtomicReference<>();
        f.operations.addCompletionListener(completion -> outcome.set(completion.outcome()));
        when(f.scanner.streamSupportedFiles(root)).thenReturn(java.util.stream.Stream.of(root.resolve("a.fb2")));

        ImportResult result = f.useCase.execute(ImportContext.builder()
                .rootDirectory(root)
                .cancelFlag(new AtomicBoolean(true))
                .indexAfterSave(false)
                .batchSize(1000)
                .build());

        assertThat(result.status()).isEqualTo(ImportStatus.CANCELLED);
        assertThat(outcome.get()).isEqualTo(LibraryOperationOutcome.CANCELLED);
        assertThat(f.operations.isBusy()).isFalse();
        verify(f.importFile).assertExpectedCollection(any());
        verify(f.importFile, never()).execute(any());
    }

    @Test
    void fatalDirectoryFailureReportsFailedLibraryOperationOutcome() throws Exception {
        Fixture f = new Fixture();
        Path root = Path.of("library");
        AtomicReference<LibraryOperationOutcome> outcome = new AtomicReference<>();
        f.operations.addCompletionListener(completion -> outcome.set(completion.outcome()));
        when(f.scanner.streamSupportedFiles(root)).thenThrow(new java.io.IOException("scan failed"));

        RuntimeException failure = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                f.useCase.execute(ImportContext.builder()
                        .rootDirectory(root)
                        .indexAfterSave(false)
                        .batchSize(1000)
                        .build()));

        assertThat(failure).hasMessageContaining("Помилка сканування каталогу");
        assertThat(outcome.get()).isEqualTo(LibraryOperationOutcome.FAILED);
        assertThat(f.operations.isBusy()).isFalse();
    }

    @Test
    void positiveImportWithoutExactIdsFallsBackToFullRebuild() throws Exception {
        Fixture f = new Fixture();
        Path root = Path.of("library");
        when(f.scanner.streamSupportedFiles(root)).thenReturn(java.util.stream.Stream.of(root.resolve("legacy.dat")));
        when(f.importFile.execute(any())).thenReturn(success(5, ImportChangeSet.empty(true)));

        ImportResult result = f.useCase.execute(ImportContext.builder()
                .rootDirectory(root)
                .indexAfterSave(true)
                .batchSize(1000)
                .build());

        assertThat(result.status()).isEqualTo(ImportStatus.SUCCESS);
        verify(f.indexRebuilder).rebuildIndex();
        verifyNoInteractions(f.searchSync);
    }

    @Test
    void deletedOnlyChangesAreSynchronizedEvenWhenImportedCountIsZero() throws Exception {
        Fixture f = new Fixture();
        Path root = Path.of("library");
        BookId deleted = BookId.generate();
        when(f.scanner.streamSupportedFiles(root)).thenReturn(java.util.stream.Stream.of(root.resolve("catalog.inpx")));
        when(f.importFile.execute(any())).thenReturn(success(0,
                new ImportChangeSet(Set.of(), Set.of(), Set.of(deleted.asString()), true)));
        when(f.searchSync.synchronizeSafelyNow(any())).thenReturn(true);

        f.useCase.execute(ImportContext.builder()
                .rootDirectory(root)
                .indexAfterSave(true)
                .batchSize(1000)
                .build());

        verify(f.searchSync).synchronizeSafelyNow(List.of(deleted));
        verifyNoInteractions(f.indexRebuilder);
    }

    @Test
    void completionProgressIsEmittedOnlyAfterRequestedSearchSynchronization() throws Exception {
        Fixture f = new Fixture();
        Path root = Path.of("library");
        BookId inserted = BookId.generate();
        when(f.scanner.streamSupportedFiles(root)).thenReturn(java.util.stream.Stream.of(root.resolve("a.fb2")));
        when(f.importFile.execute(any())).thenReturn(success(1,
                new ImportChangeSet(Set.of(inserted.asString()), Set.of(), Set.of(), true)));
        List<String> order = new ArrayList<>();
        when(f.searchSync.synchronizeSafelyNow(any())).thenAnswer(invocation -> {
            order.add("sync");
            return true;
        });

        f.useCase.execute(ImportContext.builder()
                .rootDirectory(root)
                .indexAfterSave(true)
                .progressListener(value -> order.add("progress:" + value))
                .batchSize(1000)
                .build());

        assertThat(order).containsSubsequence("sync", "progress:1.0");
        assertThat(order.get(order.size() - 1)).isEqualTo("progress:1.0");
    }

    @Test
    void aggregatesChildDiagnosticsIntoDirectoryResult() throws Exception {
        Fixture f = new Fixture();
        Path root = Path.of("library");
        ImportIssue warning = new ImportIssue(ImportSeverity.WARNING, "normalize", "INVALID_ISBN",
                "record-42", "Invalid ISBN ignored", false, Map.of("value", "bad-isbn"));
        when(f.scanner.streamSupportedFiles(root)).thenReturn(java.util.stream.Stream.of(root.resolve("catalog.inpx")));
        when(f.importFile.execute(any())).thenReturn(new ImportResult(
                0, 0, 0, 0, 1, ImportStatus.SUCCESS_WITH_WARNINGS, ImportChangeSet.empty(true), List.of(warning)));

        ImportResult result = f.useCase.execute(ImportContext.builder()
                .rootDirectory(root).indexAfterSave(false).batchSize(1000).build());

        assertThat(result.status()).isEqualTo(ImportStatus.SUCCESS_WITH_WARNINGS);
        assertThat(result.issues()).containsExactly(warning);
    }

    @Test
    void failedChildProducesBoundedDiagnosticWithoutLeakingAbsolutePath() throws Exception {
        Fixture f = new Fixture();
        Path root = Path.of("/private/library");
        Path broken = root.resolve("broken.fb2");
        when(f.scanner.streamSupportedFiles(root)).thenReturn(java.util.stream.Stream.of(broken));
        when(f.importFile.execute(any())).thenThrow(new IllegalStateException("failed at /private/library/broken.fb2"));

        ImportResult result = f.useCase.execute(ImportContext.builder()
                .rootDirectory(root).indexAfterSave(false).batchSize(1000).build());

        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("FILE_IMPORT_FAILED");
        assertThat(result.issues().get(0).sourceRecord()).isEqualTo("broken.fb2");
        assertThat(result.issues().get(0).message()).doesNotContain("/private/library");
        assertThat(result.issues().get(0).context()).containsEntry("exceptionType", "IllegalStateException");
    }

    @Test
    void cancellationExceptionStopsDirectoryWithoutCountingItAsAnError() throws Exception {
        Fixture f = new Fixture();
        Path root = Path.of("library");
        when(f.scanner.streamSupportedFiles(root)).thenReturn(java.util.stream.Stream.of(
                root.resolve("a.fb2"), root.resolve("b.fb2")));
        when(f.importFile.execute(any())).thenThrow(new CancellationException("cancelled"));

        ImportResult result = f.useCase.execute(ImportContext.builder()
                .rootDirectory(root).indexAfterSave(false).batchSize(1000).build());

        assertThat(result.status()).isEqualTo(ImportStatus.CANCELLED);
        assertThat(result.errors()).isZero();
        verify(f.importFile, times(1)).execute(any());
    }

    @Test
    void aggregateDiagnosticsAreBoundedAndReportOmittedCount() throws Exception {
        Fixture f = new Fixture();
        Path root = Path.of("library");
        List<Path> files = java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> root.resolve("book-" + i + ".fb2")).toList();
        when(f.scanner.streamSupportedFiles(root)).thenReturn(files.stream());
        ImportIssue first = new ImportIssue(ImportSeverity.WARNING, "normalize", "WARN_A", "1", "Warning A", false, Map.of());
        ImportIssue second = new ImportIssue(ImportSeverity.WARNING, "normalize", "WARN_B", "2", "Warning B", false, Map.of());
        when(f.importFile.execute(any())).thenReturn(new ImportResult(0, 0, 0, 0, 1,
                ImportStatus.SUCCESS_WITH_WARNINGS, ImportChangeSet.empty(true), List.of(first, second)));

        ImportResult result = f.useCase.execute(ImportContext.builder()
                .rootDirectory(root).indexAfterSave(false).batchSize(1000).build());

        assertThat(result.issues()).hasSize(100);
        assertThat(result.issues().get(99).code()).isEqualTo("ISSUES_TRUNCATED");
        assertThat(result.issues().get(99).context()).containsEntry("omittedCount", "21");
    }

    @Test
    void childWarningCannotBeCollapsedIntoDirectorySuccess() throws Exception {
        Fixture f = new Fixture();
        Path root = Path.of("library");
        when(f.scanner.streamSupportedFiles(root)).thenReturn(java.util.stream.Stream.of(root.resolve("catalog.dat")));
        when(f.importFile.execute(any())).thenReturn(new ImportResult(
                0, 1, 0, 0, 1, ImportStatus.SUCCESS_WITH_WARNINGS, ImportChangeSet.empty(true), List.of()));

        ImportResult result = f.useCase.execute(ImportContext.builder()
                .rootDirectory(root)
                .indexAfterSave(false)
                .batchSize(1000)
                .build());

        assertThat(result.status()).isEqualTo(ImportStatus.SUCCESS_WITH_WARNINGS);
    }

    private static ImportResult success(long imported, ImportChangeSet changes) {
        return new ImportResult(imported, 0, 0, 0, 1,
                ImportStatus.SUCCESS, changes, List.of());
    }

    private static final class Fixture {
        final ImportFileUseCase importFile = mock(ImportFileUseCase.class);
        final LibraryScanner scanner = mock(LibraryScanner.class);
        final EventPublisher events = mock(EventPublisher.class);
        final IndexRebuilder indexRebuilder = mock(IndexRebuilder.class);
        final SearchIndexSynchronizer searchSync = mock(SearchIndexSynchronizer.class);
        final BulkImportOptimizer bulk = mock(BulkImportOptimizer.class);
        final DatabaseInitializerPort dbInit = mock(DatabaseInitializerPort.class);
        final LibraryOperationCoordinator operations = new LibraryOperationCoordinator();
        final ImportDirectoryUseCase useCase = new ImportDirectoryUseCase(
                importFile, scanner, events, indexRebuilder, searchSync, bulk, dbInit, operations);
    }
}
