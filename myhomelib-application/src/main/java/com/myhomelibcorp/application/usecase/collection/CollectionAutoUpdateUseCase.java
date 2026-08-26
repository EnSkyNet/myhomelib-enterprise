package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.collection.CollectionSourceState;
import com.myhomelibcorp.application.port.out.collection.CollectionSourceMonitorPort;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Application facade for collection source configuration and manual refresh fallback. */
@Component
@RequiredArgsConstructor
public class CollectionAutoUpdateUseCase {
    public static final int DEFAULT_DEBOUNCE_SECONDS = 60;

    private final CollectionSourceMonitorPort monitorPort;
    private final ExecutorPort executorPort;

    public Optional<CollectionSourceState> load(String collectionId) {
        return monitorPort.findState(collectionId);
    }

    public CompletableFuture<CollectionSourceState> configure(String collectionId, Path sourceFile, boolean enabled) {
        return executorPort.submit(() -> monitorPort.configure(
                collectionId, sourceFile, enabled, DEFAULT_DEBOUNCE_SECONDS));
    }

    public CompletableFuture<CollectionSourceState> checkNow(String collectionId) {
        return executorPort.submit(() -> monitorPort.checkNow(collectionId));
    }

    public CompletableFuture<CollectionSourceState> markApplied(String collectionId, Path importedSource) {
        return executorPort.submit(() -> monitorPort.markApplied(collectionId, importedSource));
    }

    public void stop(String collectionId) {
        monitorPort.stopMonitoring(collectionId);
    }
}
