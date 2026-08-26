package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.collection.CollectionMaintenanceReport;
import com.myhomelibcorp.application.collection.MaintenanceApplyResult;
import com.myhomelibcorp.application.port.out.collection.CollectionMaintenancePort;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** analyze -> preview/dry-run -> explicit apply. No destructive action occurs during analyze/dry-run. */
@Component
@RequiredArgsConstructor
public class CollectionMaintenanceUseCase {
    private final CollectionMaintenancePort maintenancePort;
    private final ExecutorPort executorPort;

    public CompletableFuture<CollectionMaintenanceReport> analyze(String collectionId) {
        return executorPort.submit(() -> maintenancePort.analyze(collectionId));
    }

    public CompletableFuture<MaintenanceApplyResult> dryRun(String collectionId, Set<String> issueIds) {
        return executorPort.submit(() -> maintenancePort.apply(collectionId, issueIds, true));
    }

    public CompletableFuture<MaintenanceApplyResult> apply(String collectionId, Set<String> issueIds) {
        return executorPort.submit(() -> maintenancePort.apply(collectionId, issueIds, false));
    }
}
