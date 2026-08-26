package com.myhomelibcorp.application.port.out.collection;

import com.myhomelibcorp.application.collection.CollectionSourceState;

import java.nio.file.Path;
import java.util.Optional;

/** Infrastructure boundary for WatchService-based local collection source monitoring. */
public interface CollectionSourceMonitorPort {
    Optional<CollectionSourceState> findState(String collectionId);
    CollectionSourceState configure(String collectionId, Path sourceFile, boolean enabled, int debounceSeconds);
    CollectionSourceState checkNow(String collectionId);
    CollectionSourceState markApplied(String collectionId, Path importedSource);
    void startMonitoring(String collectionId);
    void stopMonitoring(String collectionId);
}
