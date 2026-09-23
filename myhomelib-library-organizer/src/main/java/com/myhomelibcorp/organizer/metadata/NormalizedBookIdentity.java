package com.myhomelibcorp.organizer.metadata;

import java.math.BigDecimal;
import java.util.List;

public record NormalizedBookIdentity(String title, List<NormalizedAuthor> authors, String series,
                                     BigDecimal seriesNumber, String language, String isbn,
                                     int confidence, List<NormalizationIssue> issues) {
    public NormalizedBookIdentity {
        title = title == null ? "" : title; authors = List.copyOf(authors == null ? List.of() : authors);
        series = series == null ? "" : series; language = language == null ? "" : language;
        isbn = isbn == null ? "" : isbn; confidence = Math.max(0, Math.min(100, confidence));
        issues = List.copyOf(issues == null ? List.of() : issues);
    }
    public String firstAuthorDisplayName() { return authors.isEmpty() ? "" : authors.get(0).displayName(); }
}
