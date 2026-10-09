package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.infrastructure.ImportIndexMaintenancePort;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/** Restores deferred SQLite performance indexes after the user-visible catalog UPDATE releases its lease. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeferredImportIndexMaintenanceService {
    private final ImportIndexMaintenancePort indexMaintenance;
    private final ExecutorPort executor;
    private final LibraryOperationCoordinator operations;
    private final CollectionLifecyclePort collections;

    public CompletableFuture<Void> scheduleAfterCurrentOperation(String expectedCollectionId) {
        return executor.submit(() -> {
            LibraryOperationCoordinator.Lease lease = operations.acquireDetachedSharedAwait(LibraryOperationType.INDEX);
            try {
                Collection active = collections.getCurrentCollection();
                String actualId = active == null || active.getId() == null ? "" : active.getId().trim();
                String expected = expectedCollectionId == null ? "" : expectedCollectionId.trim();
                if (active == null || (!expected.isBlank() && !expected.equals(actualId))) {
                    log.info("Deferred SQLite index restore skipped because active collection changed");
                    lease.markCompleted("Deferred SQLite index restore skipped: active collection changed");
                    return null;
                }
                if (!indexMaintenance.hasPendingIndexes()) {
                    lease.markCompleted("No deferred SQLite indexes to restore");
                    return null;
                }
                long started = System.nanoTime();
                indexMaintenance.restorePendingIndexes();
                long durationMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
                log.info("Deferred SQLite index restore completed in {} ms", durationMs);
                lease.markCompleted("Deferred SQLite indexes restored");
                return null;
            } catch (RuntimeException | Error failure) {
                lease.markFailed(failure);
                log.error("Deferred SQLite index restore failed; durable queue remains for startup recovery", failure);
                throw failure;
            } finally {
                lease.close();
            }
        });
    }
}
