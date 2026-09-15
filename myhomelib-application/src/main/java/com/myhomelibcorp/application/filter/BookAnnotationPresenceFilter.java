package com.myhomelibcorp.application.filter;

/** Optional user-data filter for books that contain Reader notes/highlights. */
public enum BookAnnotationPresenceFilter {
    ANY,
    NOTES,
    HIGHLIGHTS,
    NOTES_OR_HIGHLIGHTS
}
