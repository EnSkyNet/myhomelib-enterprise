package com.myhomelibcorp.application.search;

/** Read-only health projection for the currently active derived search index. */
public record SearchIndexHealth(boolean fresh, String detail) {
    public SearchIndexHealth {
        detail = detail == null ? "" : detail;
    }

    public static SearchIndexHealth unknown(String detail) {
        return new SearchIndexHealth(false, detail == null ? "Search index state is unknown" : detail);
    }
}
