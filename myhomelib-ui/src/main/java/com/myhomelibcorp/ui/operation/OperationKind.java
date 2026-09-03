package com.myhomelibcorp.ui.operation;

/** Stable operation category used by UI projections such as collection runtime state. */
public enum OperationKind {
    COLLECTION_CREATE,
    COLLECTION_DELETE,
    CATALOG_IMPORT,
    CATALOG_UPDATE,
    INDEX_REBUILD,
    BACKUP,
    RESTORE,
    INTEGRITY_CHECK,
    MAINTENANCE,
    BOOK_DOWNLOAD,
    GENERIC
}
