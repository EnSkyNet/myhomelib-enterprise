package com.myhomelibcorp.application.content.indexing;

public record ContentIndexingQueueSnapshot(
        boolean paused, boolean pausedForBattery, IndexingResourceProfile profile,
        int pending, int active, long completed, long failed, String lastError) {
    public ContentIndexingQueueSnapshot { lastError = lastError == null ? "" : lastError; }
}
