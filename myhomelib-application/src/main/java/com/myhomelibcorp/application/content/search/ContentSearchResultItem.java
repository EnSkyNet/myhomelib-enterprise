package com.myhomelibcorp.application.content.search;

public record ContentSearchResultItem(
        String bookId, String artifactId, String bookTitle, String authors,
        String chapterId, String chapterTitle, String snippet, long matchOffset, float score) {
    public ContentSearchResultItem {
        bookId = safe(bookId);
        artifactId = safe(artifactId);
        bookTitle = safe(bookTitle);
        authors = safe(authors);
        chapterId = safe(chapterId);
        chapterTitle = safe(chapterTitle);
        snippet = safe(snippet);
        matchOffset = Math.max(0L, matchOffset);
    }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
