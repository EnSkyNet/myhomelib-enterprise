package com.myhomelibcorp.reader.api;

import java.util.List;

public record BookMetadata(
        String id,
        String title,
        List<String> authors,
        String language,
        String series,
        Integer sequenceNumber,
        List<String> genres,
        String annotation,
        String publisher,
        String year,
        String isbn,
        long fileSize
) {
    public static BookMetadata empty() {
        return new BookMetadata(
                "",
                "Без назви",
                List.of("Невідомий автор"),
                "uk",
                null,
                null,
                List.of(),
                "",
                "",
                "",
                null,
                0
        );
    }

    public boolean isEmpty() {
        return title == null || title.isBlank();
    }

    public String getAuthorsText() {
        return String.join(", ", authors);
    }

    public String getGenresText() {
        return String.join(", ", genres);
    }
}