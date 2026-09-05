package com.myhomelibcorp.application.usecase.imports;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.error.ImportErrorHandler;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.port.out.catalog.CatalogImportPort;
import com.myhomelibcorp.application.port.out.event.EventPublisher;
import com.myhomelibcorp.application.port.out.importer.FastImportService;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportFileUseCaseFastInpxIndexTest {

    @Test
    void appliesCompleteFastInpxChangesSelectivelyInsteadOfCommitOnly() {
        Fixture f = new Fixture();
        BookId id = BookId.generate();
        ImportChangeSet changes = new ImportChangeSet(Set.of(id.asString()), Set.of(), Set.of(), true);
        when(f.fast.importInpx(any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(result(1, changes));
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(id);
        when(book.isDeleted()).thenReturn(false);
        when(f.books.findByIds(anyList())).thenReturn(List.of(book));

        f.useCase.execute(context(true));

        verify(f.search).beginAtomicUpdate();
        verify(f.search).indexBook(book);
        verify(f.search).commit();
        verify(f.search, never()).rebuildIndex();
    }

    @Test
    void rebuildsWhenFastInpxChangeIdsExceededBoundedTrackingLimit() {
        Fixture f = new Fixture();
        ImportChangeSet changes = new ImportChangeSet(Set.of(), Set.of(), Set.of(), false, 60_000, 0, 0);
        when(f.fast.importInpx(any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(result(60_000, changes));

        f.useCase.execute(context(true));

        verify(f.search).rebuildIndex();
        verify(f.search, never()).beginAtomicUpdate();
        verify(f.search, never()).commit();
    }

    @Test
    void unchangedFastInpxDoesNotTouchLuceneWhenIndexAfterSaveIsTrue() {
        Fixture f = new Fixture();
        when(f.fast.importInpx(any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(new ImportResult(0, 562_307, 0, 0, 1,
                        ImportStatus.SUCCESS, ImportChangeSet.empty(true), List.of()));

        f.useCase.execute(context(true));

        verifyNoInteractions(f.search);
    }

    @Test
    void recognizesUppercaseInpxIndependentlyOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Fixture f = new Fixture();
            when(f.fast.importInpx(any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                    .thenReturn(new ImportResult(0, 0, 0, 0, 1,
                            ImportStatus.SUCCESS, ImportChangeSet.empty(true), List.of()));

            ImportContext context = ImportContext.builder()
                    .file(Path.of("CATALOG.INPX"))
                    .batchSize(1000)
                    .indexAfterSave(false)
                    .catalogFullSnapshot(true)
                    .build();
            f.useCase.execute(context);

            verify(f.fast).importInpx(any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void leavesSearchFinalizationToOuterOrchestratorWhenIndexAfterSaveIsFalse() {
        Fixture f = new Fixture();
        BookId id = BookId.generate();
        ImportChangeSet changes = new ImportChangeSet(Set.of(id.asString()), Set.of(), Set.of(), true);
        when(f.fast.importInpx(any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(result(1, changes));

        f.useCase.execute(context(false));

        verifyNoInteractions(f.search);
    }

    private static ImportContext context(boolean indexAfterSave) {
        return ImportContext.builder()
                .file(Path.of("catalog.inpx"))
                .batchSize(1000)
                .indexAfterSave(indexAfterSave)
                .catalogFullSnapshot(true)
                .build();
    }

    private static ImportResult result(long imported, ImportChangeSet changes) {
        return new ImportResult(imported, 0, 0, 0, 1,
                ImportStatus.SUCCESS, changes, List.of());
    }

    private static final class Fixture {
        final FastImportService fast = mock(FastImportService.class);
        final SearchIndexer search = mock(SearchIndexer.class);
        final BookQueryRepository books = mock(BookQueryRepository.class);
        final ImportFileUseCase useCase = new ImportFileUseCase(
                mock(ImporterRegistry.class),
                mock(BookSaver.class),
                mock(ImportErrorHandler.class),
                mock(EventPublisher.class),
                mock(BulkImportOptimizer.class),
                fast,
                search,
                mock(CatalogImportPort.class),
                books,
                new LibraryOperationCoordinator());
    }
}
