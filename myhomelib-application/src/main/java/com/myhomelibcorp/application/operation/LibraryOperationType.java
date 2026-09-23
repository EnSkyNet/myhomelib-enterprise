package com.myhomelibcorp.application.operation;

/** Coordinated collection operations that must not race with destructive library lifecycle changes. */
public enum LibraryOperationType {
    IMPORT,
    UPDATE,
    INDEX,
    BACKUP,
    RESTORE,
    VACUUM,
    SWITCH,
    DELETE,
    CREATE,
    SYNC,
    DOWNLOAD,
    EDIT,
    ANNOTATION,
    CONVERSION,
    COPY,
    EXPORT,
    QUERY,
    READER_STATE,
    INTEGRITY_AUDIT,
    ORGANIZE
}
