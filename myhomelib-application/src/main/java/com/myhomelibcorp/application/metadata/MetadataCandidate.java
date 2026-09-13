package com.myhomelibcorp.application.metadata;

import com.myhomelibcorp.domain.model.valueobject.Isbn;

import java.util.LinkedHashSet;
import java.util.List;

/** Normalized metadata proposal returned by any provider. No field is applied automatically. */
public record MetadataCandidate(
        MetadataSource source,
        double confidence,
        String title,
        List<String> authors,
        String isbn,
        Integer year,
        String publisher,
        String language,
        String annotation,
        String coverUrl
) {
    public MetadataCandidate {
        if (source == null) throw new IllegalArgumentException("source is required");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
        title = clean(title);
        authors = normalizeAuthors(authors);
        isbn = normalizeOptionalIsbn(isbn);
        if (year != null && (year < 0 || year > 9999)) {
            throw new IllegalArgumentException("year must be between 0 and 9999");
        }
        publisher = clean(publisher);
        language = clean(language);
        annotation = clean(annotation);
        coverUrl = clean(coverUrl);
    }

    private static List<String> normalizeAuthors(List<String> source) {
        if (source == null || source.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : source) {
            String author = clean(value);
            if (!author.isBlank()) normalized.add(author);
        }
        return List.copyOf(normalized);
    }

    private static String normalizeOptionalIsbn(String value) {
        String normalized = clean(value);
        if (normalized.isBlank()) return "";
        return Isbn.of(normalized).value();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
