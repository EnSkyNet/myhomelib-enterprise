package com.myhomelibcorp.infrastructure.content;

import com.myhomelibcorp.application.content.ContentAnchor;
import com.myhomelibcorp.application.content.ExtractedChapter;
import com.myhomelibcorp.application.content.ExtractedContent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Shared bounded builder that keeps chapter and paragraph offsets globally consistent. */
final class ContentDocumentBuilder {
    static final int MAX_TEXT_CHARS = 64 * 1024 * 1024;

    private final String sourceId;
    private final String format;
    private final StringBuilder text = new StringBuilder();
    private final List<ExtractedChapter> chapters = new ArrayList<>();

    ContentDocumentBuilder(String sourceId, String format) {
        this.sourceId = required(sourceId, "sourceId");
        this.format = required(format, "format");
    }

    void addChapter(String id, String title, List<ParagraphBlock> blocks) throws IOException {
        List<ParagraphBlock> effective = blocks == null ? List.of() : blocks.stream()
                .filter(java.util.Objects::nonNull)
                .map(block -> new ParagraphBlock(block.id(), normalize(block.text())))
                .filter(block -> !block.text().isBlank())
                .toList();
        if (effective.isEmpty()) return;

        if (!chapters.isEmpty()) appendChecked("\n");
        long chapterStart = text.length();
        StringBuilder chapterText = new StringBuilder();
        List<ContentAnchor> anchors = new ArrayList<>(effective.size());
        for (int i = 0; i < effective.size(); i++) {
            ParagraphBlock block = effective.get(i);
            if (i > 0) chapterText.append('\n');
            long start = chapterStart + chapterText.length();
            chapterText.append(block.text());
            long end = chapterStart + chapterText.length();
            anchors.add(new ContentAnchor(
                    id + ":" + block.id(), id, block.id(), start, end));
        }
        appendChecked(chapterText.toString());
        long chapterEnd = text.length();
        chapters.add(new ExtractedChapter(id, title, chapterStart, chapterEnd, chapterText.toString(), anchors));
    }

    ExtractedContent build() {
        return new ExtractedContent(sourceId, format, text.toString(), chapters);
    }

    private void appendChecked(String value) throws IOException {
        if ((long) text.length() + value.length() > MAX_TEXT_CHARS) {
            throw new IOException("Extracted text exceeds safety limit of " + MAX_TEXT_CHARS + " characters");
        }
        text.append(value);
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder out = new StringBuilder(value.length());
        boolean whitespace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '\u00A0') {
                whitespace = out.length() > 0;
            } else {
                if (whitespace) out.append(' ');
                out.append(c);
                whitespace = false;
            }
        }
        return out.toString().trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    record ParagraphBlock(String id, String text) {
        ParagraphBlock {
            id = required(id, "paragraph id");
            text = text == null ? "" : text;
        }
    }
}
