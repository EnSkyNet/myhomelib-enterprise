package com.myhomelibcorp.application.port.out.download;

import com.myhomelibcorp.application.download.DownloadQueueEntry;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Persistence port for the restart-safe online-book download queue. */
public interface DownloadQueuePort {
    void markPending(String collectionId, String bookId, String physicalArchiveIdentity, String resumeInformation);

    void markInProgress(String collectionId, String bookId);

    void markCompleted(String collectionId, String bookId, Path destination);

    void markFailed(String collectionId, String bookId, String safeError, String resumeInformation);

    void markCancelled(String collectionId, String bookId, String resumeInformation);

    Optional<DownloadQueueEntry> find(String collectionId, String bookId);

    List<DownloadQueueEntry> findByStatus(com.myhomelibcorp.application.download.DownloadQueueStatus status, int limit);

    /** Converts stale IN_PROGRESS rows from a previous process into resumable PENDING rows. */
    int recoverInterrupted();
}
