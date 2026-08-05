package com.myhomelibcorp.application.port.out.infrastructure;

import com.myhomelibcorp.domain.model.sync.SyncOptions;
import com.myhomelibcorp.domain.model.sync.SyncResult;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Порт для синхронізації папок з бібліотекою.
 */
public interface FolderSyncPort {

    /**
     * Синхронізує папку з бібліотекою.
     */
    SyncResult syncFolder(Path directory, SyncOptions options);

    /**
     * Асинхронна синхронізація папки.
     */
    CompletableFuture<SyncResult> syncFolderAsync(Path directory, SyncOptions options);

    /**
     * Перевіряє, чи виконується синхронізація.
     */
    boolean isSyncing();

    /**
     * Скасовує поточну синхронізацію.
     */
    void cancelSync();
}