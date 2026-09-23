package com.myhomelibcorp.application.usecase.reading;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.repository.ContinueReadingRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ContinueReadingServiceLifecycleTest {

    @Test
    void queryIsRejectedWhileCollectionSwitchOwnsLifecycle() {
        ContinueReadingRepository repository = mock(ContinueReadingRepository.class);
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        ContinueReadingService service = new ContinueReadingService(repository, coordinator);

        try (var ignored = coordinator.acquire(LibraryOperationType.SWITCH)) {
            assertThrows(RuntimeException.class, () -> service.recent(10));
        }

        verifyNoInteractions(repository);
    }
}
