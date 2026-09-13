package com.myhomelibcorp.application.folderwatch;

import java.nio.file.Path;
import java.time.Instant;

/** Persisted MHL-113 configuration and aggregate queue state for one collection incoming folder. */
public record IncomingFolderWatchState(
        String collectionId,
        Path folder,
        boolean enabled,
        int debounceSeconds,
        int stabilitySeconds,
        Instant lastScanAt,
        String status,
        int waitingCount,
        int readyCount,
        int processingCount,
        int importedCount,
        int duplicateContentCount,
        int failedCount
) {
    public boolean configured() { return folder != null; }
    public int pendingCount() { return waitingCount + readyCount + processingCount; }
}
