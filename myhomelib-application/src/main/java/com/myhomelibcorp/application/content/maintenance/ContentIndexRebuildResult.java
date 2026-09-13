package com.myhomelibcorp.application.content.maintenance;

import com.myhomelibcorp.application.content.index.ContentIndexHealth;

public record ContentIndexRebuildResult(Status status, long processedBooks, long indexedArtifacts, ContentIndexHealth health, String message) {
    public enum Status { COMPLETED, CANCELLED, FAILED }
    public ContentIndexRebuildResult {
        status = status == null ? Status.FAILED : status;
        processedBooks = Math.max(0L, processedBooks);
        indexedArtifacts = Math.max(0L, indexedArtifacts);
        message = message == null ? "" : message;
    }
}
