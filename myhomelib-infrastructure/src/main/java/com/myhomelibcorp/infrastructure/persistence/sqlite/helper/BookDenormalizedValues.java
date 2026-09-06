package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.author.AuthorNameKey;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;

/** Central derivation for indexed denormalized book columns introduced by V18. */
public final class BookDenormalizedValues {
    private BookDenormalizedValues() { }

    public static String format(String fileName) {
        return SupportedFormatRegistry.standard().searchFormat(fileName);
    }

    public static String authorSort(Book book) {
        if (book == null || book.getAuthors() == null || book.getAuthors().isEmpty()) return "";
        return book.getAuthors().stream()
                .filter(java.util.Objects::nonNull)
                .map(BookDenormalizedValues::authorSort)
                .filter(v -> !v.isBlank())
                .min(Comparator.naturalOrder())
                .orElse("");
    }

    /**
     * Derives the same stable sort key from structured author-name identities used by the fast INPX path.
     * No delimiter serialization is involved, so legal characters in names cannot corrupt identity/sorting.
     */
    public static String authorSort(Collection<AuthorNameKey> authors) {
        if (authors == null || authors.isEmpty()) return "";
        return authors.stream()
                .filter(java.util.Objects::nonNull)
                .map(BookDenormalizedValues::authorSort)
                .filter(v -> !v.isBlank())
                .min(Comparator.naturalOrder())
                .orElse("");
    }

    private static String authorSort(AuthorNameKey author) {
        return normalizeAuthorName(author.lastName(), author.firstName(), author.middleName());
    }

    private static String authorSort(Author author) {
        return normalizeAuthorName(author.getLastName(), author.getFirstName(), author.getMiddleName());
    }

    private static String normalizeAuthorName(String lastName, String firstName, String middleName) {
        return (safe(lastName) + " " + safe(firstName) + " " + safe(middleName))
                .trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

}
