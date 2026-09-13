package com.myhomelibcorp.application.content.indexing;

import java.util.List;

public record ContentIndexQueueCheckpoint(String collectionId, boolean paused, List<ContentIndexingTask> tasks) {
    public ContentIndexQueueCheckpoint {
        if (collectionId == null || collectionId.isBlank()) throw new IllegalArgumentException("collectionId is required");
        collectionId = collectionId.trim();
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
