package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class BookBatchEditSupportTest {
    private final BookCommandRepository repository = mock(BookCommandRepository.class);
    private final SearchIndexSynchronizer synchronizer = mock(SearchIndexSynchronizer.class);
    private final BookBatchEditSupport support = new BookBatchEditSupport(
            repository, synchronizer, new LibraryOperationCoordinator(), null);

    @Test
    void mutatesThenSynchronizesTheSameIds() {
        List<BookId> ids = List.of(BookId.generate(), BookId.generate());

        support.execute(ids, repo -> repo.updateProgressBatch(ids, 75));

        verify(repository).updateProgressBatch(ids, 75);
        verify(synchronizer).synchronizeAfterCommit(ids);
    }

    @Test
    void mutationFailureDoesNotScheduleIndexSynchronization() {
        List<BookId> ids = List.of(BookId.generate());
        doThrow(new IllegalStateException("write failed")).when(repository).updateRateBatch(ids, 4);

        assertThrows(IllegalStateException.class,
                () -> support.execute(ids, repo -> repo.updateRateBatch(ids, 4)));

        verifyNoInteractions(synchronizer);
    }

    @Test
    void emptyBatchDoesNothing() {
        support.execute(List.of(), repo -> repo.updateProgressBatch(List.of(), 100));
        verifyNoInteractions(repository, synchronizer);
    }
}
