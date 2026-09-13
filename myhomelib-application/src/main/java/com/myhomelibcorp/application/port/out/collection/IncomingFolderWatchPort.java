package com.myhomelibcorp.application.port.out.collection;

import com.myhomelibcorp.application.folderwatch.IncomingFolderCandidate;
import com.myhomelibcorp.application.folderwatch.IncomingFolderWatchState;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** MHL-113 persistence + WatchService boundary. Import execution stays in application layer. */
public interface IncomingFolderWatchPort {
    Optional<IncomingFolderWatchState> findState(String collectionId);
    IncomingFolderWatchState configure(String collectionId, Path folder, boolean enabled,
                                       int debounceSeconds, int stabilitySeconds);
    IncomingFolderWatchState scanNow(String collectionId);
    List<IncomingFolderCandidate> listReady(String collectionId, int limit);
    boolean claimReady(String collectionId, Path file, String fingerprint);
    void markImported(String collectionId, Path file, String fingerprint,
                      long imported, long duplicates, long errors);
    void markFailed(String collectionId, Path file, String fingerprint, String error);
    void startMonitoring(String collectionId);
    void stopMonitoring(String collectionId);
}
