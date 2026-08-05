package com.myhomelibcorp.application.usecase.sync;

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
        return folderSyncPort.syncFolder(directory, options);
    }

    public CompletableFuture<SyncResult> executeAsync(Path directory, SyncOptions options) {
        log.info("📂 Початок асинхронної синхронізації папки: {}", directory);
        return folderSyncPort.syncFolderAsync(directory, options);
    }

    public boolean isSyncing() {
        return folderSyncPort.isSyncing();
    }

    public void cancelSync() {
        folderSyncPort.cancelSync();
        log.info("⏹ Синхронізацію скасовано");
    }
}