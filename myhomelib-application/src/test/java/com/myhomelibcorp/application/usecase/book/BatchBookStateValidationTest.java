package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class BatchBookStateValidationTest {

    private final BookCommandRepository repository = mock(BookCommandRepository.class);
    private final SearchIndexSynchronizer synchronizer = mock(SearchIndexSynchronizer.class);
    private final LibraryOperationCoordinator operationCoordinator = new LibraryOperationCoordinator();

    @Test
    void ratingRejectsValuesOutsideOneToFiveBeforeRepositoryMutation() {
        UpdateRateBatchUseCase useCase = new UpdateRateBatchUseCase(repository, synchronizer, operationCoordinator);
        List<BookId> ids = List.of(BookId.generate());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(ids, 0));
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(ids, 6));
        verifyNoInteractions(repository, synchronizer);
    }

    @Test
    void progressRejectsValuesOutsideZeroToHundredBeforeRepositoryMutation() {
        UpdateProgressBatchUseCase useCase = new UpdateProgressBatchUseCase(repository, synchronizer, operationCoordinator);
        List<BookId> ids = List.of(BookId.generate());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(ids, -1));
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(ids, 101));
        verifyNoInteractions(repository, synchronizer);
    }

    @Test
    void nullBookIdsAreRejectedBeforeRepositoryMutation() {
        UpdateProgressBatchUseCase useCase = new UpdateProgressBatchUseCase(repository, synchronizer, operationCoordinator);
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(java.util.Arrays.asList(BookId.generate(), null), 50));
        verifyNoInteractions(repository, synchronizer);
    }
}
