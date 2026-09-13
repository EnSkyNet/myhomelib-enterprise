package com.myhomelibcorp.application.content.indexing;

public record ContentIndexingProgress(String taskId, String stage, long completed, long total) {
    public ContentIndexingProgress {
        stage = stage == null ? "" : stage;
        completed = Math.max(0L, completed);
        total = Math.max(0L, total);
    }
}
