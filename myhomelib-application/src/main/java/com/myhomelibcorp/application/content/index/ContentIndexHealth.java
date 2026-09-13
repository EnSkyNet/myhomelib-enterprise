package com.myhomelibcorp.application.content.index;

/** Observable state of one collection's independent full-text index. */
public record ContentIndexHealth(
        String collectionId,
        int schemaVersion,
        long documentCount,
        long sizeBytes,
        boolean compatible,
        String message
) {
    public ContentIndexHealth {
        collectionId = collectionId == null ? "" : collectionId.trim();
        documentCount = Math.max(0L, documentCount);
        sizeBytes = Math.max(0L, sizeBytes);
        message = message == null ? "" : message.trim();
    }
}
