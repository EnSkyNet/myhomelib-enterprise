package com.myhomelibcorp.application.opds;

public record OpdsBookQuery(
        String authorId,
        String series,
        String genreCode,
        String text,
        int offset,
        int limit) {
    public OpdsBookQuery {
        authorId = clean(authorId);
        series = clean(series);
        genreCode = clean(genreCode);
        text = clean(text);
        offset = Math.max(0, offset);
        limit = Math.max(1, Math.min(100, limit));
    }
    public static OpdsBookQuery all(int offset, int limit) {
        return new OpdsBookQuery("", "", "", "", offset, limit);
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
