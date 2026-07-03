package com.myhomelibcorp.application.query.common;

public record Pagination(int limit, int offset) {
    public static Pagination of(int limit, int offset) {
        return new Pagination(limit, offset);
    }

    public static Pagination defaultPagination() {
        return new Pagination(100, 0);
    }

    public Pagination withLimit(int newLimit) {
        return new Pagination(newLimit, offset);
    }

    public Pagination withOffset(int newOffset) {
        return new Pagination(limit, newOffset);
    }
}