package com.myhomelibcorp.reader.model;

import java.util.List;

/**
 * Контейнер для HTML та розділів книги.
 * Використовується для кешування контенту в ReaderContentService.
 */
public record ReaderBookContent(
        String html,
        List<Chapter> chapters,
        long sizeBytes
) {
    public ReaderBookContent(String html, List<Chapter> chapters) {
        this(html, chapters, html != null ? html.getBytes().length : 0);
    }

    public boolean isEmpty() {
        return html == null || html.isEmpty();
    }

    public boolean hasChapters() {
        return chapters != null && !chapters.isEmpty();
    }

    @Override
    public String toString() {
        return "ReaderBookContent{" +
                "htmlLength=" + (html != null ? html.length() : 0) +
                ", chapters=" + (chapters != null ? chapters.size() : 0) +
                ", sizeBytes=" + sizeBytes +
                '}';
    }
}