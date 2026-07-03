package com.myhomelibcorp.infrastructure.persistence.sqlite.query;

public final class BookGenreQueries {

    private BookGenreQueries() {}

    public static final String FIND_GENRES_BY_BOOK = """
            SELECT g.code, g.name, g.parent_code, g.fb2_code
            FROM genres g
            JOIN book_genres bg ON g.code = bg.genre_code
            WHERE bg.book_id = ?
            """;

    public static final String FIND_GENRES_FOR_BOOKS = """
            SELECT bg.book_id, g.code, g.name, g.parent_code, g.fb2_code
            FROM book_genres bg
            JOIN genres g ON bg.genre_code = g.code
            WHERE bg.book_id IN (%s)
            """;

    public static final String DELETE_BY_BOOK = "DELETE FROM book_genres WHERE book_id = ?";
    public static final String INSERT_BOOK_GENRE = "INSERT OR IGNORE INTO book_genres (book_id, genre_code) VALUES (?, ?)";
    public static final String DELETE_BY_GENRE = "DELETE FROM book_genres WHERE genre_code = ?";
}