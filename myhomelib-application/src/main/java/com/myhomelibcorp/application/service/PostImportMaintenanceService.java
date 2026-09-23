package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.application.usecase.series.SyncSeriesUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Runs collection-scoped derived-state maintenance only after the preceding lifecycle operation is released.
 * The originating collection id is revalidated under EDIT before any write/read against the dynamic
 * active collection, so a queued refresh can never drift into a collection selected in the meantime.
 */
@Service
@RequiredArgsConstructor
public class PostImportMaintenanceService {
    private final SyncSeriesUseCase syncSeriesUseCase;
    private final StatisticsService statisticsService;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final LibraryOperationCoordinator operationCoordinator;

    public LibraryStatistics refreshAfterImport(String expectedCollectionId) {
        return refreshCollectionDerivedState(expectedCollectionId);
    }

    public LibraryStatistics refreshCollectionDerivedState(String expectedCollectionId) {
        LibraryOperationCoordinator.Lease lease = operationCoordinator.acquireDetachedSharedAwait(LibraryOperationType.EDIT);
        try {
            assertExpectedCollection(expectedCollectionId);
            syncSeriesUseCase.execute();
            statisticsService.refreshStatistics();
            LibraryStatistics statistics = statisticsService.getStatistics();
            lease.markCompleted("Collection derived-state maintenance completed");
            return statistics;
        } catch (RuntimeException failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private void assertExpectedCollection(String expectedCollectionId) {
        Collection active = collectionLifecyclePort.getCurrentCollection();
        if (active == null) throw new IllegalStateException("Колекцію не вибрано");
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) return;
        String activeId = active.getId() == null ? "" : active.getId().trim();
        if (!expectedCollectionId.trim().equals(activeId)) {
            throw new IllegalStateException("Активна колекція змінилася до оновлення похідних даних");
        }
    }
}
