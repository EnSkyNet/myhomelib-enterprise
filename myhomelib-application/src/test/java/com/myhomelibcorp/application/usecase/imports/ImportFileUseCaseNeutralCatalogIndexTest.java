package com.myhomelibcorp.application.usecase.imports;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.error.ImportErrorHandler;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
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
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ImportFileUseCaseNeutralCatalogIndexTest {

    @Test
    void keepsNeutralFullSnapshotOnSafeRebuildPathEvenWhenAdapterReportsCompleteIds() {
        Fixture f = new Fixture();
        BookId id = BookId.generate();
        ImportChangeSet exact = new ImportChangeSet(Set.of(id.asString()), Set.of(), Set.of(), true);
        when(f.catalog.importCatalog(any())).thenReturn(result(1, exact));

        f.useCase.execute(context(true));

        verify(f.search).rebuildIndex();
        verify(f.search, never()).beginAtomicUpdate();
        verify(f.search, never()).commit();
    }

    @Test
    void rebuildsNeutralFullSnapshotWhenExactIdsAreUnavailable() {
        Fixture f = new Fixture();
        ImportChangeSet incomplete = new ImportChangeSet(Set.of(), Set.of(), Set.of(), false, 25_000, 0, 0);
        when(f.catalog.importCatalog(any())).thenReturn(result(25_000, incomplete));

        f.useCase.execute(context(true));

        verify(f.search).rebuildIndex();
        verify(f.search, never()).beginAtomicUpdate();
    }

    @Test
    void appliesCompleteNeutralDeltaSelectively() {
        Fixture f = new Fixture();
        BookId id = BookId.generate();
        ImportChangeSet exact = new ImportChangeSet(Set.of(id.asString()), Set.of(), Set.of(), true);
        when(f.catalog.importCatalog(any())).thenReturn(result(1, exact));
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(id);
        when(book.isDeleted()).thenReturn(false);
        when(f.books.findByIds(anyList())).thenReturn(List.of(book));

        f.useCase.execute(context(false));

        verify(f.search).beginAtomicUpdate();
        verify(f.search).indexBook(book);
        verify(f.search).commit();
        verify(f.search, never()).rebuildIndex();
    }

    private static ImportContext context(boolean fullSnapshot) {
        return ImportContext.builder()
                .file(Path.of("catalog.json"))
                .catalogFullSnapshot(fullSnapshot)
                .indexAfterSave(true)
                .build();
    }

    private static ImportResult result(long imported, ImportChangeSet changes) {
        return new ImportResult(imported, 0, 0, 0, 1,
                ImportStatus.SUCCESS, changes, List.of());
    }

    private static final class Fixture {
        final SearchIndexer search = mock(SearchIndexer.class);
        final CatalogImportPort catalog = mock(CatalogImportPort.class);
        final BookQueryRepository books = mock(BookQueryRepository.class);
        final ImportFileUseCase useCase = new ImportFileUseCase(
                mock(ImporterRegistry.class),
                mock(BookSaver.class),
                mock(ImportErrorHandler.class),
                mock(EventPublisher.class),
                mock(BulkImportOptimizer.class),
                mock(FastImportService.class),
                search,
                catalog,
                books,
                new LibraryOperationCoordinator());

        Fixture() {
            when(catalog.supports(any())).thenReturn(true);
        }
    }
}
