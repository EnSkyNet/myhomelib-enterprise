package com.myhomelibcorp.application.progress;

/** Application-layer stages shared by long-running operations; deliberately independent of JavaFX. */
public enum OperationStage {
    CHECKING_SERVER,
    DOWNLOADING,
    VALIDATING,
    READING_CATALOG,
    IMPORTING,
    UPDATING_AUTHORS,
    APPLYING_DELETIONS,
    UPDATING_SEARCH_INDEX,
    INTEGRITY_CHECKS,
    FINALIZING,
    BOOK_DOWNLOAD,
    COMPLETED,
    CANCELLED,
    FAILED
}
