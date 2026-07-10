package com.myhomelibcorp.infrastructure.persistence.sql;

public final class GenreSql {
    private GenreSql() {}

    public static final String FIND_ALL = "SELECT code, name, parent_code, fb2_code FROM genres";
    public static final String FIND_BY_CODE = "SELECT code, name, parent_code, fb2_code FROM genres WHERE code = ?";
    public static final String FIND_BY_PARENT = "SELECT code, name, parent_code, fb2_code FROM genres WHERE parent_code = ?";

    public static final String INSERT_OR_UPDATE = """
        INSERT INTO genres (code, name, parent_code, fb2_code)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(code) DO UPDATE SET
            name = excluded.name,
            parent_code = excluded.parent_code,
            fb2_code = excluded.fb2_code
        """;

    public static final String DELETE_BY_CODE = "DELETE FROM genres WHERE code = ?";
    public static final String COUNT_ALL = "SELECT COUNT(*) FROM genres";
}