package com.myhomelibcorp.application.health;

public enum LibraryHealthIssueType {
    MISSING_ARTIFACTS,
    CORRUPT_ARTIFACTS,
    CHANGED_ARTIFACTS,
    DUPLICATES,
    METADATA_GAPS,
    DATABASE_INTEGRITY,
    STALE_SEARCH_INDEX,
    BACKUP_AGE
}
