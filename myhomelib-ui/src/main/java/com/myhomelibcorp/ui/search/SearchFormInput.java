package com.myhomelibcorp.ui.search;

import java.time.LocalDate;

/** Immutable snapshot of Search Workspace controls for one request. */
record SearchFormInput(
        String freeText,
        String title,
        String author,
        String series,
        String genre,
        String keyword,
        String annotation,
        String file,
        String language,
        String ratingFrom,
        String ratingTo,
        String yearFrom,
        String yearTo,
        LocalDate addedFrom,
        LocalDate addedTo,
        boolean localOnly
) {
    SearchFormInput {
        freeText = safe(freeText);
        title = safe(title);
        author = safe(author);
        series = safe(series);
        genre = safe(genre);
        keyword = safe(keyword);
        annotation = safe(annotation);
        file = safe(file);
        language = safe(language);
        ratingFrom = safe(ratingFrom);
        ratingTo = safe(ratingTo);
        yearFrom = safe(yearFrom);
        yearTo = safe(yearTo);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
