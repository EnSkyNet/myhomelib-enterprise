package com.myhomelibcorp.application.search;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.GenreDto;

import java.util.List;

/**
 * Type-safe result of the global library search.
 * Keeps UI code independent from stringly typed Map payloads.
 */
public record GlobalSearchResult(
        List<AuthorDto> authors,
        List<String> series,
        List<GenreDto> genres,
        List<BookDto> books
) {
    public GlobalSearchResult {
        authors = authors == null ? List.of() : List.copyOf(authors);
        series = series == null ? List.of() : List.copyOf(series);
        genres = genres == null ? List.of() : List.copyOf(genres);
        books = books == null ? List.of() : List.copyOf(books);
    }

    public static GlobalSearchResult empty() {
        return new GlobalSearchResult(List.of(), List.of(), List.of(), List.of());
    }
}
