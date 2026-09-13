package com.myhomelibcorp.application.content.indexing;

import java.time.Instant;

public record ContentIndexingTask(
        String taskId, String collectionId, String bookId, String artifactId,
        String sourcePath, String format, ContentIndexingPriority priority, Instant enqueuedAt) {
    public ContentIndexingTask {
        taskId = required(taskId, "taskId");
        collectionId = required(collectionId, "collectionId");
        bookId = required(bookId, "bookId");
        artifactId = required(artifactId, "artifactId");
        sourcePath = required(sourcePath, "sourcePath");
        format = required(format, "format").toLowerCase(java.util.Locale.ROOT);
        priority = priority == null ? ContentIndexingPriority.NORMAL : priority;
        enqueuedAt = enqueuedAt == null ? Instant.now() : enqueuedAt;
    }
    private static String required(String v, String field) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(field + " is required");
        return v.trim();
    }
}
