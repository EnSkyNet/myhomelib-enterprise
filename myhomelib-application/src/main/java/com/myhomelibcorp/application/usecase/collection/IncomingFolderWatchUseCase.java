package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.folderwatch.IncomingFolderWatchState;
import com.myhomelibcorp.application.port.out.collection.IncomingFolderWatchPort;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** JavaFX-independent settings facade for MHL-113 incoming-folder automation. */
@Component
@RequiredArgsConstructor
public class IncomingFolderWatchUseCase {
    public static final int DEFAULT_DEBOUNCE_SECONDS = 2;
    public static final int DEFAULT_STABILITY_SECONDS = 3;

    private final IncomingFolderWatchPort watcher;
    private final ExecutorPort executor;

    public Optional<IncomingFolderWatchState> load(String collectionId) {
        return watcher.findState(collectionId);
    }

    public CompletableFuture<IncomingFolderWatchState> configure(String collectionId, Path folder, boolean enabled) {
        return executor.submit(() -> watcher.configure(collectionId, folder, enabled,
                DEFAULT_DEBOUNCE_SECONDS, DEFAULT_STABILITY_SECONDS));
    }

    public CompletableFuture<IncomingFolderWatchState> scanNow(String collectionId) {
        return executor.submit(() -> watcher.scanNow(collectionId));
    }

    public void stop(String collectionId) { watcher.stopMonitoring(collectionId); }
}
