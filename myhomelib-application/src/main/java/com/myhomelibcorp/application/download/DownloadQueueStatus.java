package com.myhomelibcorp.application.download;

/** Durable lifecycle of an online-book download request. */
public enum DownloadQueueStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}
