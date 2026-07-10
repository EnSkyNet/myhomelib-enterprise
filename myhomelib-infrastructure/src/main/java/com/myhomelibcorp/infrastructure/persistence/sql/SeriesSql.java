package com.myhomelibcorp.infrastructure.persistence.sql;

public final class SeriesSql {
    private SeriesSql() {}

    public static final String FIND_ALL = "SELECT id, name FROM series ORDER BY name";
    public static final String FIND_BY_ID = "SELECT id, name FROM series WHERE id = ?";
    public static final String FIND_DISTINCT_NAMES = """
        SELECT DISTINCT TRIM(series)
        FROM books
        WHERE series IS NOT NULL AND TRIM(series) != ''
        ORDER BY TRIM(series)
        """;

    public static final String INSERT_SERIES = "INSERT INTO series (id, name) VALUES (?, ?)";
    public static final String UPDATE_SERIES = "UPDATE series SET name = ? WHERE id = ?";
    public static final String DELETE_BY_ID = "DELETE FROM series WHERE id = ?";
    public static final String COUNT_ALL = "SELECT COUNT(*) FROM series";
}