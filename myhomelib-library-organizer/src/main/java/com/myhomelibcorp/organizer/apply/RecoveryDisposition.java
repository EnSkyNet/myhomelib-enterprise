package com.myhomelibcorp.organizer.apply;

/** Conservative recovery decision derived from the durable journal and current filesystem state. */
public enum RecoveryDisposition {
    ROLLBACK_AVAILABLE,
    NOTHING_TO_ROLLBACK,
    MANUAL_REVIEW
}
