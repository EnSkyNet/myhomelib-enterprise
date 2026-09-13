package com.myhomelibcorp.application.integrity;

/** Current non-destructive audit verdict for one local artifact. */
public enum ArtifactIntegrityStatus {
    OK,
    MISSING,
    UNREADABLE,
    CORRUPT_ARCHIVE,
    SIZE_CHANGED,
    HASH_CHANGED;

    public boolean isIssue() {
        return this != OK;
    }

    public boolean isCorrupt() {
        return this == UNREADABLE || this == CORRUPT_ARCHIVE;
    }

    public boolean isChanged() {
        return this == SIZE_CHANGED || this == HASH_CHANGED;
    }
}
