package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.author.AuthorNameKey;
import com.myhomelibcorp.domain.model.book.Book;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;

/** Central derivation for indexed denormalized book columns introduced by V18. */
public final class BookDenormalizedValues {
    private BookDenormalizedValues() { }

    public static String format(String fileName) {
        String name = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(".fb2.zip")) return "FB2ZIP";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "UNKNOWN";
        return switch (name.substring(dot + 1)) {
            case "fb2" -> "FB2";
            case "epub" -> "EPUB";
            case "pdf" -> "PDF";
            case "mobi" -> "MOBI";
            case "inpx" -> "INPX";
            case "zip" -> "ZIP";
            default -> "UNKNOWN";
        };
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
