package com.myhomelibcorp.infrastructure.persistence.sqlite;

public record SqlQuery(String sql, Object[] params) {
    public static SqlQuery of(String sql, Object... params) {
        return new SqlQuery(sql, params);
    }
}