package com.myhomelibcorp.infrastructure.persistence.sqlite.query;

public final class BookAuthorQueries {

    private BookAuthorQueries() {}

    public static final String FIND_AUTHORS_BY_BOOK = """
            SELECT a.id, a.first_name, a.middle_name, a.last_name
            FROM authors a
            JOIN book_authors ba ON a.id = ba.author_id
            WHERE ba.book_id = ?
            """;

    public static final String FIND_AUTHORS_FOR_BOOKS = """
            SELECT b.book_id, a.id, a.first_name, a.middle_name, a.last_name
            FROM book_authors b
            JOIN authors a ON b.author_id = a.id
            WHERE b.book_id IN (%s)
            """;

    public static final String DELETE_BY_BOOK = "DELETE FROM book_authors WHERE book_id = ?";
    public static final String INSERT_BOOK_AUTHOR = "INSERT OR IGNORE INTO book_authors (book_id, author_id) VALUES (?, ?)";
    public static final String DELETE_BY_AUTHOR = "DELETE FROM book_authors WHERE author_id = ?";
}