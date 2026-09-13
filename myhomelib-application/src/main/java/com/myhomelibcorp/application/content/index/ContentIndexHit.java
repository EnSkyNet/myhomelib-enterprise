package com.myhomelibcorp.application.content.index;

/** Search hit with stable chapter/global offsets and a bounded display snippet. */
public record ContentIndexHit(
        String bookId,
        String artifactId,
        String chapterId,
        String chapterTitle,
        long chapterStartOffset,
        long chapterEndOffset,
        long matchOffset,
        String snippet,
        float score
) {
    public ContentIndexHit {
        bookId = bookId == null ? "" : bookId;
        artifactId = artifactId == null ? "" : artifactId;
        chapterId = chapterId == null ? "" : chapterId;
        chapterTitle = chapterTitle == null ? "" : chapterTitle;
        matchOffset = Math.max(chapterStartOffset, matchOffset);
        snippet = snippet == null ? "" : snippet.trim();
    }
}
