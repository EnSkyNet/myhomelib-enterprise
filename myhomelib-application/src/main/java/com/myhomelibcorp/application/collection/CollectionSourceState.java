package com.myhomelibcorp.application.collection;

import java.nio.file.Path;
import java.time.Instant;

/** Persisted state of a local collection source watcher. */
public record CollectionSourceState(
        String collectionId,
        Path sourceFile,
        boolean enabled,
        int debounceSeconds,
        String baselineFingerprint,
        String observedFingerprint,
        Instant lastCheckedAt,
        boolean updateAvailable,
        String status
) {
    public boolean configured() {
        return sourceFile != null;
    }
}
