package com.myhomelibcorp.application.dto;

/** Exact aggregate counts for one author, computed in the database. */
public record AuthorBookStatistics(long books, long series, long genres) {
    public static AuthorBookStatistics empty() {
        return new AuthorBookStatistics(0, 0, 0);
    }
}
