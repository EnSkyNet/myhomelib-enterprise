package com.myhomelibcorp.application.imports.saver;

import com.myhomelibcorp.application.imports.duplicate.DuplicateDetector;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class BookSaverCollectionIsolationTest {

    @Test
    void staleExpectedCollectionIsRejectedBeforeBookLookupOrTransaction() {
        BookCommandRepository commands = mock(BookCommandRepository.class);
        BookQueryRepository queries = mock(BookQueryRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        DuplicateDetector duplicates = mock(DuplicateDetector.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        Collection active = mock(Collection.class);
        when(active.getId()).thenReturn("collection-b");
        when(lifecycle.getCurrentCollection()).thenReturn(active);

        BookSaver saver = new BookSaver(commands, queries, events, duplicates, indexer, tx,
                new LibraryOperationCoordinator(), lifecycle);

        assertThrows(IllegalStateException.class,
                () -> saver.deleteBook(BookId.fromString("00000000-0000-0000-0000-000000000001"), "collection-a"));

        verifyNoInteractions(queries, commands, tx);
    }
}
