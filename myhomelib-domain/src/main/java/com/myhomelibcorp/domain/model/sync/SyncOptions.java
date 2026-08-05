package com.myhomelibcorp.domain.model.sync;

import lombok.Builder;
import lombok.Value;

/**
 * Опції синхронізації папки.
 */
@Value
@Builder
public class SyncOptions {
    @Builder.Default
    boolean deleteOrphans = false;

    @Builder.Default
    boolean updateChanged = true;

    @Builder.Default
    boolean includeSubfolders = true;

    @Builder.Default
    boolean backupBeforeDelete = true;

    @Builder.Default
    boolean processArchives = true;

    @Builder.Default
    int maxDepth = 10;

    @Builder.Default
    long maxFileSize = 100 * 1024 * 1024; // 100 MB
}