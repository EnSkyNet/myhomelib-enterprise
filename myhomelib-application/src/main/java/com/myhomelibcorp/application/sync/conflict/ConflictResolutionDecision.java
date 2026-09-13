package com.myhomelibcorp.application.sync.conflict;

/** Outcome of resolving two concurrent sync mutations. */
public enum ConflictResolutionDecision {
    NO_CONFLICT,
    FURTHEST_PROGRESS,
    LATEST,
    MERGED,
    MANUAL
}
