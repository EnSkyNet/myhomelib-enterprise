package com.myhomelibcorp.application.content;

import java.util.List;

/** Format-neutral searchable representation produced by ContentExtractor adapters. */
public record ExtractedContent(
        String sourceId,
        String format,
        String text,
        List<ExtractedChapter> chapters
) {
    public ExtractedContent {
        sourceId = required(sourceId, "sourceId");
        format = required(format, "format").toLowerCase(java.util.Locale.ROOT);
        text = text == null ? "" : text;
        chapters = chapters == null ? List.of() : List.copyOf(chapters);
        long cursor = 0L;
        for (int i = 0; i < chapters.size(); i++) {
            ExtractedChapter chapter = chapters.get(i);
            if (i > 0) {
                if (cursor >= text.length() || text.charAt((int) cursor) != '\n') {
                    throw new IllegalArgumentException("text must equal chapters joined by newline");
                }
                cursor++;
            }
            if (chapter.startOffset() != cursor) throw new IllegalArgumentException("chapter offsets must be contiguous");
            // Validate the existing text without rebuilding another complete copy of the book.
            if (chapter.endOffset() > text.length()
                    || !text.regionMatches((int) cursor, chapter.text(), 0, chapter.text().length())) {
                throw new IllegalArgumentException("text must equal chapters joined by newline");
            }
            cursor = chapter.endOffset();
        }
        if (!chapters.isEmpty() && cursor != text.length()) {
            throw new IllegalArgumentException("text must equal chapters joined by newline");
        }
    }

    public long textLength() {
        return text.length();
    }

    public List<ContentAnchor> anchors() {
        return chapters.stream().flatMap(chapter -> chapter.anchors().stream()).toList();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
