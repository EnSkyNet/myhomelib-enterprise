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
        StringBuilder rebuilt = new StringBuilder(text.length());
        for (int i = 0; i < chapters.size(); i++) {
            ExtractedChapter chapter = chapters.get(i);
            if (chapter.startOffset() != cursor) throw new IllegalArgumentException("chapter offsets must be contiguous");
            if (i > 0) rebuilt.append('\n');
            rebuilt.append(chapter.text());
            cursor = chapter.endOffset();
            if (i + 1 < chapters.size()) cursor += 1L;
        }
        if (!chapters.isEmpty() && !rebuilt.toString().equals(text)) {
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
