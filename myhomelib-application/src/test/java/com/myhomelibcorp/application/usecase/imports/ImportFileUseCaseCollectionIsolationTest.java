package com.myhomelibcorp.application.usecase.imports;

import com.myhomelibcorp.application.content.indexing.ContentIndexAutoSchedulingService;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.error.ImportErrorHandler;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.catalog.CatalogImportPort;
import com.myhomelibcorp.application.port.out.event.EventPublisher;
import com.myhomelibcorp.application.port.out.importer.FastImportService;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ImportFileUseCaseCollectionIsolationTest {

    @Test
    void queuedImportRefusesToWriteAfterActiveCollectionChanged() {
        ImporterRegistry importers = mock(ImporterRegistry.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        Collection active = mock(Collection.class);
        when(active.getId()).thenReturn("collection-b");
        when(collections.getCurrentCollection()).thenReturn(active);

        ImportFileUseCase useCase = new ImportFileUseCase(
                importers,
                mock(BookSaver.class),
                mock(ImportErrorHandler.class),
                mock(EventPublisher.class),
                mock(BulkImportOptimizer.class),
                mock(FastImportService.class),
                mock(SearchIndexer.class),
                mock(CatalogImportPort.class),
                mock(BookQueryRepository.class),
                new LibraryOperationCoordinator(),
                collections,
                mock(ContentIndexAutoSchedulingService.class));

        ImportContext context = ImportContext.builder()
                .file(Path.of("queued-book.fb2"))
                .expectedCollectionId("collection-a")
                .build();

        assertThatThrownBy(() -> useCase.execute(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("target collection changed");

        verifyNoInteractions(importers);
    }
}
