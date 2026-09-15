package com.myhomelibcorp.application.usecase.sync;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.FolderSyncPort;
import com.myhomelibcorp.domain.model.sync.SyncOptions;
import com.myhomelibcorp.domain.model.sync.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Use Case: синхронізація папки з бібліотекою.
 */
@RequiredArgsConstructor
@Slf4j
public class SyncFolderUseCase {

    private final FolderSyncPort folderSyncPort;
    private final LibraryOperationCoordinator operationCoordinator;

    public SyncResult execute(Path directory, SyncOptions options) {
        if (directory == null) {
            throw new IllegalArgumentException("Directory cannot be null");
        }
        if (!java.nio.file.Files.exists(directory)) {
            throw new IllegalArgumentException("Directory does not exist: " + directory);
        }
        if (!java.nio.file.Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Path is not a directory: " + directory);
        }

        log.info("📂 Початок синхронізації папки: {}", directory);
        var lease = operationCoordinator.acquire(LibraryOperationType.SYNC);
        try {
            SyncResult result = folderSyncPort.syncFolder(directory, options);
            lease.markCompleted(result == null ? "Синхронізацію завершено" : result.getSummary());
            return result;
        } catch (RuntimeException failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    public CompletableFuture<SyncResult> executeAsync(Path directory, SyncOptions options) {
        if (directory == null) throw new IllegalArgumentException("Directory cannot be null");
        log.info("📂 Початок асинхронної синхронізації папки: {}", directory);
        var lease = operationCoordinator.acquireDetached(LibraryOperationType.SYNC);
        try {
            return folderSyncPort.syncFolderAsync(directory, options)
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            Throwable cause = failure instanceof java.util.concurrent.CompletionException
                                    && failure.getCause() != null ? failure.getCause() : failure;
                            if (cause instanceof java.util.concurrent.CancellationException) {
                                lease.markCancelled("Синхронізацію скасовано");
                            } else {
                                lease.markFailed(cause);
                            }
                        } else {
                            lease.markCompleted(result == null ? "Синхронізацію завершено" : result.getSummary());
                        }
                        lease.close();
                    });
        } catch (RuntimeException e) {
            lease.markFailed(e);
            lease.close();
            throw e;
        }
    }

    public boolean isSyncing() {
        return folderSyncPort.isSyncing();
    }

    public void cancelSync() {
        folderSyncPort.cancelSync();
        log.info("⏹ Синхронізацію скасовано");
    }
}