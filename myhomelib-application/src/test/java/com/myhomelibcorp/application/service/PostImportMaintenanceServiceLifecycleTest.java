package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.usecase.series.SyncSeriesUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PostImportMaintenanceServiceLifecycleTest {

    @Test
    void queuedPostImportMaintenanceRefusesDifferentActiveCollectionBeforeMutation() {
        SyncSeriesUseCase syncSeries = mock(SyncSeriesUseCase.class);
        StatisticsService statistics = mock(StatisticsService.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        Collection active = mock(Collection.class);
        when(active.getId()).thenReturn("collection-b");
        when(lifecycle.getCurrentCollection()).thenReturn(active);

        PostImportMaintenanceService service = new PostImportMaintenanceService(
                syncSeries, statistics, lifecycle, new LibraryOperationCoordinator());

        assertThatThrownBy(() -> service.refreshAfterImport("collection-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Активна колекція змінилася");

        verifyNoInteractions(syncSeries, statistics);
    }
    @Test
    void refreshCollectionDerivedStateRefusesDifferentActiveCollectionBeforeMutation() {
        SyncSeriesUseCase syncSeries = mock(SyncSeriesUseCase.class);
        StatisticsService statistics = mock(StatisticsService.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        Collection active = mock(Collection.class);
        when(active.getId()).thenReturn("collection-b");
        when(lifecycle.getCurrentCollection()).thenReturn(active);
        var service = new PostImportMaintenanceService(syncSeries, statistics, lifecycle,
                new LibraryOperationCoordinator());

        assertThatThrownBy(() -> service.refreshCollectionDerivedState("collection-a"))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(syncSeries, statistics);
    }

    @Test
    void sharedPostImportBarrierAllowsNestedStatisticsApplicationGuards() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        SyncSeriesUseCase syncSeries = mock(SyncSeriesUseCase.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        Collection active = mock(Collection.class);
        when(active.getId()).thenReturn("collection-a");
        when(lifecycle.getCurrentCollection()).thenReturn(active);

        StatisticsRepository repository = mock(StatisticsRepository.class);
        LibraryStatistics expected = mock(LibraryStatistics.class);
        doAnswer(invocation -> {
            assertEquals(com.myhomelibcorp.application.operation.LibraryOperationType.EDIT,
                    coordinator.activeOperation());
            return null;
        }).when(repository).refreshStatistics();
        when(repository.getStatistics()).thenAnswer(invocation -> {
            assertEquals(com.myhomelibcorp.application.operation.LibraryOperationType.EDIT,
                    coordinator.activeOperation());
            return expected;
        });
        StatisticsService statistics = new StatisticsService(repository, coordinator);
        PostImportMaintenanceService service = new PostImportMaintenanceService(
                syncSeries, statistics, lifecycle, coordinator);

        assertDoesNotThrow(() -> service.refreshAfterImport("collection-a"));
    }

}
