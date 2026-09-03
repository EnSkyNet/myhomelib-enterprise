package com.myhomelibcorp.application.usecase.integrity;

import java.util.List;

public record IntegrityReport(
        List<String> issues,
        long booksWithoutAuthor,
        long booksWithoutGenre,
        long orphanedAuthors,
        long orphanedGenres,
        long duplicateBooks,
        long orphanedSeries,
        long booksWithMissingSeries,
        long brokenRelations,
        boolean sqliteIntegrityOk,
        String sqliteIntegrityMessage,
        boolean luceneIntegrityOk,
        long catalogBooks,
        long luceneDocuments
) {
    public IntegrityReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
        sqliteIntegrityMessage = sqliteIntegrityMessage == null ? "" : sqliteIntegrityMessage;
    }

    /** Compatibility constructor for older tests/adapters. */
    public IntegrityReport(List<String> issues, long booksWithoutAuthor, long booksWithoutGenre,
                           long orphanedAuthors, long orphanedGenres, long duplicateBooks) {
        this(issues, booksWithoutAuthor, booksWithoutGenre, orphanedAuthors, orphanedGenres, duplicateBooks,
                0L, 0L, 0L, true, "ok", true, -1L, -1L);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }

    public long luceneDifference() {
        if (catalogBooks < 0 || luceneDocuments < 0) return -1L;
        return Math.abs(catalogBooks - luceneDocuments);
    }

    public long problemCount() {
        long count = booksWithoutAuthor + booksWithoutGenre + orphanedAuthors + orphanedGenres + duplicateBooks
                + orphanedSeries + booksWithMissingSeries + brokenRelations;
        if (!sqliteIntegrityOk) count++;
        if (!luceneIntegrityOk) count++;
        return count;
    }

    public String getSummary() {
        return String.format(
                "Книг без авторів: %d, книг без жанрів: %d, авторів без книг: %d, жанрів без книг: %d, "
                        + "дублікатів: %d, серій без книг: %d, книг з невідомою серією: %d, broken relations: %d, "
                        + "SQLite: %s, Lucene: %s (%d/%d)",
                booksWithoutAuthor, booksWithoutGenre, orphanedAuthors, orphanedGenres, duplicateBooks,
                orphanedSeries, booksWithMissingSeries, brokenRelations,
                sqliteIntegrityOk ? "OK" : "ERROR", luceneIntegrityOk ? "OK" : "ERROR",
                luceneDocuments, catalogBooks
        );
    }
}
