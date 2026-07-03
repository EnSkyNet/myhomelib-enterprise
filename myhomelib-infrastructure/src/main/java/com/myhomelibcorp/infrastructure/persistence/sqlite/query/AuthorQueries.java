package com.myhomelibcorp.infrastructure.persistence.sqlite.query;

public final class AuthorQueries {

    private AuthorQueries() {}

    public static final String FIND_ALL = "SELECT * FROM authors";
    public static final String FIND_BY_ID = "SELECT * FROM authors WHERE id = ?";
    public static final String FIND_BY_FULL_NAME = "SELECT * FROM authors WHERE first_name = ? AND last_name = ?";
    public static final String FIND_BY_SEARCH_NAME = "SELECT * FROM authors WHERE search_name LIKE ?";

    public static final String INSERT_OR_UPDATE_AUTHOR = """
            INSERT INTO authors (id, first_name, middle_name, last_name, search_name)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                first_name = excluded.first_name,
                middle_name = excluded.middle_name,
                last_name = excluded.last_name,
                search_name = excluded.search_name
            """;

    public static final String DELETE_BY_ID = "DELETE FROM authors WHERE id = ?";
    public static final String UPDATE_SEARCH_NAME = "UPDATE authors SET search_name = ? WHERE id = ?";
}