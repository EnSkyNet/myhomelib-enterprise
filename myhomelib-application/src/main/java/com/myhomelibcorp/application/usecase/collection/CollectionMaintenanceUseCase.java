package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.collection.CollectionMaintenanceReport;
import com.myhomelibcorp.application.collection.MaintenanceApplyResult;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.collection.CollectionMaintenancePort;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/** analyze -> preview/dry-run -> explicit apply. No destructive action occurs during analyze/dry-run. */
@Component
@RequiredArgsConstructor
public class CollectionMaintenanceUseCase {
    private final CollectionMaintenancePort maintenancePort;
    private final ExecutorPort executorPort;
    private final LibraryOperationCoordinator operationCoordinator;

    public CompletableFuture<CollectionMaintenanceReport> analyze(String collectionId) {
        return submitMaintenance(() -> maintenancePort.analyze(collectionId));
    }

    public CompletableFuture<MaintenanceApplyResult> dryRun(String collectionId, Set<String> issueIds) {
        return submitMaintenance(() -> maintenancePort.apply(collectionId, issueIds, true));
    }

    public CompletableFuture<MaintenanceApplyResult> apply(String collectionId, Set<String> issueIds) {
        return submitMaintenance(() -> maintenancePort.apply(collectionId, issueIds, false));
    }

    private <T> CompletableFuture<T> submitMaintenance(Callable<T> task) {
        final LibraryOperationCoordinator.Lease lease;
        try {
            lease = operationCoordinator.acquireDetached(LibraryOperationType.INTEGRITY_AUDIT);
        } catch (RuntimeException lifecycleBusy) {
            return CompletableFuture.failedFuture(lifecycleBusy);
        }
        try {
            CompletableFuture<T> future = executorPort.submit(task);
            return future.whenComplete((value, failure) -> {
                if (failure == null) lease.markCompleted("");
                else lease.markFailed(failure);
                lease.close();
            });
        } catch (RuntimeException | Error schedulingFailure) {
            lease.markFailed(schedulingFailure);
            lease.close();
            return CompletableFuture.failedFuture(schedulingFailure);
        }
    }
}
