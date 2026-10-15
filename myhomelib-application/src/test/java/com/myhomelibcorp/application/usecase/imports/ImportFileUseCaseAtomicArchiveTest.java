package com.myhomelibcorp.application.usecase.imports;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.duplicate.DuplicatePolicy;
import com.myhomelibcorp.application.imports.error.ImportErrorHandler;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.catalog.CatalogImportPort;
import com.myhomelibcorp.application.port.out.event.EventPublisher;
import com.myhomelibcorp.application.port.out.importer.*;
import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportFileUseCaseAtomicArchiveTest {
    @TempDir Path temp;

    private record Fixture(ImportFileUseCase service, BookImporterPort importer,
                           BookSaver saver, SearchIndexer index, EventPublisher events,
                           List<Book> committed, List<Book> staged, Path source) {}

    private Fixture create(String filename, boolean cancelOnFirstSave, AtomicBoolean cancelled) throws IOException {
        Path file = temp.resolve(filename);
        Files.writeString(file, "test archive placeholder");
        BookImporterPort importer = mock(BookImporterPort.class);
        when(importer.countBooks(file)).thenReturn(2L);
        ImporterRegistry registry = mock(ImporterRegistry.class);
        when(registry.findImporter(file)).thenReturn(importer);
        BookSaver saver = mock(BookSaver.class);
        SearchIndexer index = mock(SearchIndexer.class);
        EventPublisher events = mock(EventPublisher.class);
        List<Book> committed = new ArrayList<>();
        List<Book> staged = new ArrayList<>();
        when(saver.saveBatchWithResult(anyList(), eq(false), eq(DuplicatePolicy.SKIP))).thenAnswer(call -> {
            List<Book> chunk = List.copyOf(call.<List<Book>>getArgument(0));
            staged.addAll(chunk);
            if (cancelOnFirstSave && staged.size() == 1) cancelled.set(true);
            return new BookSaver.BatchSaveResult(chunk, List.of(), List.of());
        });
        when(saver.inAtomicArchiveImport(any())).thenAnswer(call -> {
            try {
                Object result = ((Supplier<?>) call.getArgument(0)).get();
                committed.addAll(staged); // Simulated DB commit
                return result;
            } finally {
                staged.clear(); // Simulated DB rollback on exception
            }
        });
        ImportFileUseCase service = new ImportFileUseCase(registry, saver,
                mock(ImportErrorHandler.class), events, mock(BulkImportOptimizer.class),
                mock(FastImportService.class), index, mock(CatalogImportPort.class),
                mock(BookQueryRepository.class), new LibraryOperationCoordinator());
        return new Fixture(service, importer, saver, index, events, committed, staged, file);
    }

    private Book book(int id) {
        Book result = mock(Book.class);
        when(result.getId()).thenReturn(BookId.fromString("00000000-0000-0000-0000-" + String.format("%012d", id)));
        return result;
    }

    @Test
    void lateArchiveReadFailureDoesNotPublishPartialBatchesOrFinishEvent() throws Exception {
        Fixture f = create("broken.tar", false, new AtomicBoolean());
        Book first = book(1);
        Iterator<Book> broken = new Iterator<>() {
            int position;
            public boolean hasNext() {
                if (position > 0) throw new UncheckedIOException(new IOException("broken TAR tail"));
                return true;
            }
            public Book next() { position++; return first; }
        };
        when(f.importer().importBooks(f.source())).thenReturn(
                StreamSupport.stream(Spliterators.spliteratorUnknownSize(broken, Spliterator.ORDERED), false));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> f.service().execute(ImportContext.builder().file(f.source())
                        .batchSize(1).build()));
        assertTrue(error.getMessage().contains("архів"));
        assertTrue(f.committed().isEmpty(), "First batch must roll back after tail failure");
        verify(f.saver(), times(1)).saveBatchWithResult(anyList(), anyBoolean(), any());
        verify(f.index(), never()).rebuildIndex();
        verifyNoInteractions(f.events());
    }

    @Test
    void cancelledArchiveRollsBackAllBatchesAndReportsZeroCommitted() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        Fixture f = create("cancelled.zip", true, cancelled);
        Book first = book(1);
        Book second = book(2);
        when(f.importer().importBooks(f.source())).thenReturn(Stream.of(first, second));
        var result = f.service().execute(ImportContext.builder().file(f.source())
                .batchSize(1).cancelFlag(cancelled).build());
        assertEquals(ImportStatus.CANCELLED, result.status());
        assertEquals(0, result.imported());
        assertTrue(f.committed().isEmpty());
        verify(f.index(), never()).rebuildIndex();
    }

    @Test
    void cancelledNonArchiveImportNeverReportsHundredPercent() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        Fixture f = create("cancelled.fb2", true, cancelled);
        Book first = book(1);
        Book second = book(2);
        when(f.importer().importBooks(f.source())).thenReturn(Stream.of(first, second));
        List<Double> progress = new ArrayList<>();

        var result = f.service().execute(ImportContext.builder().file(f.source())
                .batchSize(1).indexAfterSave(false).cancelFlag(cancelled)
                .progressListener(progress::add).build());

        assertEquals(ImportStatus.CANCELLED, result.status());
        assertFalse(progress.contains(1.0), "Cancelled non-archive import must not report completion");
    }

    @Test
    void lateCancellationAfterAtomicArchiveCommitFinishesIndexingAndReportsCompletion() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        Fixture f = create("late-cancel.zip", false, cancelled);
        Book first = book(1);
        Book second = book(2);
        when(f.importer().importBooks(f.source())).thenReturn(Stream.of(first, second));
        doAnswer(invocation -> { cancelled.set(true); return null; }).when(f.index()).beginAtomicUpdate();
        List<Double> progress = new ArrayList<>();

        var result = f.service().execute(ImportContext.builder().file(f.source())
                .batchSize(1).indexAfterSave(true).cancelFlag(cancelled)
                .progressListener(progress::add).build());

        assertEquals(ImportStatus.SUCCESS, result.status(), "A cancellation request after archive commit cannot undo the committed import");
        assertTrue(progress.contains(1.0), "Completion is reported after committed archive indexing finishes");
        verify(f.index()).commit();
    }

    @Test
    void validArchiveCommitsBothBatchesBeforePublishingFinish() throws Exception {
        Fixture f = create("valid.fb2zip", false, new AtomicBoolean());
        Book first = book(1);
        Book second = book(2);
        when(f.importer().importBooks(f.source())).thenReturn(Stream.of(first, second));
        var result = f.service().execute(ImportContext.builder().file(f.source())
                .batchSize(1).indexAfterSave(false).build());
        assertEquals(ImportStatus.SUCCESS, result.status());
        assertEquals(2, result.imported());
        assertEquals(2, f.committed().size());
        verify(f.events()).publish(any());
    }
}
