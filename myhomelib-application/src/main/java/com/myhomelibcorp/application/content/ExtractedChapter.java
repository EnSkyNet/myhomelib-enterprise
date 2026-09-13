package com.myhomelibcorp.application.content;

import java.util.List;

/** One logical chapter plus paragraph-level anchors into the complete extracted text. */
public record ExtractedChapter(
        String id,
        String title,
        long startOffset,
        long endOffset,
        String text,
        List<ContentAnchor> anchors
) {
    public ExtractedChapter {
        id = required(id, "id");
        title = title == null ? "" : title.trim();
        text = text == null ? "" : text;
        anchors = anchors == null ? List.of() : List.copyOf(anchors);
        if (startOffset < 0L) throw new IllegalArgumentException("startOffset must be >= 0");
        if (endOffset < startOffset) throw new IllegalArgumentException("endOffset must be >= startOffset");
        if (endOffset - startOffset != text.length()) {
            throw new IllegalArgumentException("chapter offsets must match text length");
        }
        for (ContentAnchor anchor : anchors) {
            if (!id.equals(anchor.chapterId())) throw new IllegalArgumentException("anchor chapterId mismatch");
            if (anchor.startOffset() < startOffset || anchor.endOffset() > endOffset) {
                throw new IllegalArgumentException("anchor must stay inside chapter offsets");
            }
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
