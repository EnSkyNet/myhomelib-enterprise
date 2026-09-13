package com.myhomelibcorp.application.folderwatch;

import java.nio.file.Path;
import java.time.Instant;

/** Stable persisted file candidate emitted by the incoming-folder watcher. */
public record IncomingFolderCandidate(
        String collectionId,
        Path file,
        String fingerprint,
        long size,
        long modifiedMillis,
        IncomingFolderCandidateStatus status,
        Instant detectedAt,
        String lastError
) { }
