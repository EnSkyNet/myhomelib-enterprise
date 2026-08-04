package com.myhomelibcorp.application.usecase.integrity;

import java.util.List;

public record IntegrityReport(
        List<String> issues,
        long booksWithoutAuthor,
        long booksWithoutGenre,
        long orphanedAuthors,
        long orphanedGenres,
        long duplicateBooks
) {
    public boolean hasIssues() {
        return !issues.isEmpty();
    }

    public String getSummary() {
        return String.format(
                "Книг без авторів: %d, книг без жанрів: %d, авторів без книг: %d, жанрів без книг: %d, дублікатів книг: %d",
                booksWithoutAuthor, booksWithoutGenre, orphanedAuthors, orphanedGenres, duplicateBooks
        );
    }
}