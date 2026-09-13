package com.myhomelibcorp.application.duplicate.fuzzy;

/** Human-readable evidence categories for a fuzzy duplicate suggestion. */
public enum FuzzyDuplicateReason {
    ISBN_EXACT,
    TITLE_EXACT,
    TITLE_SIMILAR,
    AUTHOR_EXACT,
    AUTHOR_SIMILAR,
    YEAR_MATCH
}
