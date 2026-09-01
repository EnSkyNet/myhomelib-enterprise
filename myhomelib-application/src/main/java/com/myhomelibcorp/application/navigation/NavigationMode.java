package com.myhomelibcorp.application.navigation;

/**
 * Stable application-level navigation modes. UI controls must depend on this
 * enum instead of declaring their own navigation state.
 */
public enum NavigationMode {
    AUTHORS,
    SERIES,
    GENRES,
    YEARS,
    LANGUAGES,
    ARCHIVES,
    KEYWORDS,
    GROUPS,
    REVIEWS,
    UPDATES,
    DOWNLOADED,
    ALREADY_READ,
    HISTORY,
    ALL_BOOKS
}
