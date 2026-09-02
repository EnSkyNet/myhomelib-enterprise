package com.myhomelibcorp.application.operation;

/** Mutating/maintenance operations that must not race on one library lifecycle. */
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
    SYNC
}
