package com.myhomelibcorp.application.statistics;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatisticsServiceLifecycleTest {

    @Test
    void readHoldsQueryBarrierAroundRepositoryAccess() {
        StatisticsRepository repository = mock(StatisticsRepository.class);
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        LibraryStatistics expected = mock(LibraryStatistics.class);
        when(repository.getStatistics()).thenAnswer(invocation -> {
            assertEquals(LibraryOperationType.QUERY, coordinator.activeOperation());
            return expected;
        });

        StatisticsService service = new StatisticsService(repository, coordinator);

        assertEquals(expected, service.getStatistics());
        assertFalse(coordinator.isBusy());
    }

    @Test
    void refreshAndInvalidateHoldEditBarrier() {
        StatisticsRepository repository = mock(StatisticsRepository.class);
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        doAnswer(invocation -> {
            assertEquals(LibraryOperationType.EDIT, coordinator.activeOperation());
            return null;
        }).when(repository).invalidate();
        doAnswer(invocation -> {
            assertEquals(LibraryOperationType.EDIT, coordinator.activeOperation());
            return null;
        }).when(repository).refreshStatistics();

        StatisticsService service = new StatisticsService(repository, coordinator);

        service.invalidate();
        service.refreshStatistics();
        assertFalse(coordinator.isBusy());
    }

    @Test
    void synchronousOuterLifecycleOperationReusesItsStableCollectionContext() {
        StatisticsRepository repository = mock(StatisticsRepository.class);
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        StatisticsService service = new StatisticsService(repository, coordinator);

        try (var ignored = coordinator.acquire(LibraryOperationType.IMPORT)) {
            assertDoesNotThrow(service::invalidate);
            assertDoesNotThrow(service::refreshStatistics);
            assertEquals(LibraryOperationType.IMPORT, coordinator.activeOperation());
        }
        assertFalse(coordinator.isBusy());
    }
}
