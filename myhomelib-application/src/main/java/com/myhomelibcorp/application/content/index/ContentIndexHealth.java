package com.myhomelibcorp.application.content.index;

/** Observable state of one collection's independent full-text index. */
public record ContentIndexHealth(
        String collectionId,
        int schemaVersion,
        long documentCount,
        long sizeBytes,
        boolean compatible,
        State state,
        String technicalDetail
) {
    public enum State { ABSENT, EMPTY, READY, STALE, SCHEMA_MISMATCH, ERROR }

    public ContentIndexHealth {
        collectionId = collectionId == null ? "" : collectionId.trim();
        documentCount = Math.max(0L, documentCount);
        sizeBytes = Math.max(0L, sizeBytes);
        state = state == null ? State.ERROR : state;
        technicalDetail = technicalDetail == null ? "" : technicalDetail.trim();
    }
}
