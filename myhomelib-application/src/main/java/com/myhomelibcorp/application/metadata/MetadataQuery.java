package com.myhomelibcorp.application.metadata;

import com.myhomelibcorp.domain.model.valueobject.Isbn;

/** Vendor-neutral metadata lookup criteria. At least one criterion is required. */
public record MetadataQuery(
        String isbn,
        String title,
        String author,
        int limit
) {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public MetadataQuery {
        isbn = normalizeIsbn(isbn);
        title = normalizeText(title);
        author = normalizeText(author);
        if (isbn.isBlank() && title.isBlank() && author.isBlank()) {
            throw new IllegalArgumentException("At least one metadata search criterion is required");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Metadata search limit must be between 1 and " + MAX_LIMIT);
        }
    }

    public static MetadataQuery byIsbn(String isbn) {
        return new MetadataQuery(isbn, "", "", DEFAULT_LIMIT);
    }

    public static MetadataQuery byTitle(String title) {
        return new MetadataQuery("", title, "", DEFAULT_LIMIT);
    }

    public static MetadataQuery byAuthor(String author) {
        return new MetadataQuery("", "", author, DEFAULT_LIMIT);
    }

    public MetadataQuery withLimit(int newLimit) {
        return new MetadataQuery(isbn, title, author, newLimit);
    }

    public boolean hasIsbn() {
        return !isbn.isBlank();
    }

    public boolean hasTitle() {
        return !title.isBlank();
    }

    public boolean hasAuthor() {
        return !author.isBlank();
    }

    private static String normalizeIsbn(String value) {
        String normalized = normalizeText(value);
        if (normalized.isBlank()) return "";
        return Isbn.of(normalized).value();
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }
}
