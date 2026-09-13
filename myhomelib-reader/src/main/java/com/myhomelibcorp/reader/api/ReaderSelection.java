package com.myhomelibcorp.reader.api;

import java.util.Objects;

/**
 * Immutable renderer-neutral snapshot of a text selection.
 *
 * <p>Offsets always refer to the parsed document text, never to pixels or pagination. The quote
 * plus bounded prefix/suffix context can therefore be converted into a durable annotation anchor
 * without coupling the reader module to the annotation domain.</p>
 */
public record ReaderSelection(
        long startOffset,
        long endOffset,
        String text,
        int chapterIndex,
        String chapterId,
        String chapterTitle,
        int paragraphIndex,
        String paragraphId,
        double position,
        String prefix,
        String suffix
) {
    public ReaderSelection {
        if (startOffset < 0) throw new IllegalArgumentException("startOffset must be >= 0");
        if (endOffset <= startOffset) throw new IllegalArgumentException("selection must be non-empty");
        text = Objects.requireNonNullElse(text, "");
        if (text.isBlank()) throw new IllegalArgumentException("selection text must not be blank");
        chapterId = normalize(chapterId);
        chapterTitle = normalize(chapterTitle);
        paragraphId = normalize(paragraphId);
        if (!Double.isFinite(position) || position < 0.0 || position > 1.0) {
            throw new IllegalArgumentException("position must be within [0,1]");
        }
        prefix = Objects.requireNonNullElse(prefix, "");
        suffix = Objects.requireNonNullElse(suffix, "");
    }

    public long length() {
        return endOffset - startOffset;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
