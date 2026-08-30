package com.myhomelibcorp.application.download;

import java.time.Instant;

/**
 * Durable, credential-free metadata for a queued online-book download.
 * Secrets and decrypted credentials must never be persisted here.
 */
public record DownloadQueueEntry(
        String collectionId,
        String bookId,
        Instant createdAt,
        DownloadQueueStatus status,
        int retryCount,
        Instant lastAttempt,
        String downloadDestination,
        String physicalArchiveIdentity,
        String resumeInformation,
        String lastError
) { }
