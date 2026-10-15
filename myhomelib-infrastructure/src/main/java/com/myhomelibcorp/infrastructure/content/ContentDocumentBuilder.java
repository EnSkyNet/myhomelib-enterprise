package com.myhomelibcorp.infrastructure.content;

import com.myhomelibcorp.application.content.ContentAnchor;
import com.myhomelibcorp.application.content.ExtractedChapter;
import com.myhomelibcorp.application.content.ExtractedContent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Incremental builder: retain completed chapters, not another mutable copy of the whole book. */
final class ContentDocumentBuilder {
    static final int MAX_TEXT_CHARS = 64 * 1024 * 1024;
    // Tiny paragraphs can exhaust the heap through anchor objects well before the text limit.
    static final int MAX_PARAGRAPHS = 250_000;
    // EPUB head titles are retained metadata outside the normalized body-text budget.
    static final int MAX_TITLE_CHARS = 64 * 1024;
    static final int MAX_TOTAL_TITLE_CHARS = 1024 * 1024;

    private final String sourceId;
    private final String format;
    private final int maxTextChars;
    private final int maxParagraphs;
    private final List<ExtractedChapter> chapters = new ArrayList<>();
    private int textLength;
    private int paragraphCount;
    private int titleChars;
    private String chapterId;
    private int chapterStart;
    private StringBuilder chapterText;
    private List<ContentAnchor> anchors;

    ContentDocumentBuilder(String sourceId, String format) {
        this(sourceId, format, MAX_TEXT_CHARS, MAX_PARAGRAPHS);
    }

    ContentDocumentBuilder(String sourceId, String format, int maxTextChars, int maxParagraphs) {
        this.sourceId = required(sourceId, "sourceId");
        this.format = required(format, "format");
        if (maxTextChars < 0 || maxParagraphs < 0) throw new IllegalArgumentException("Negative content budget");
        this.maxTextChars = maxTextChars;
        this.maxParagraphs = maxParagraphs;
    }

    NormalizedTextBuffer paragraphBuffer() {
        // Every subsequent nonempty paragraph needs one separator, including chapter boundaries.
        return new NormalizedTextBuffer(Math.max(0, maxTextChars - textLength - (textLength == 0 ? 0 : 1)));
    }

    NormalizedTextBuffer titleBuffer() {
        return new NormalizedTextBuffer(Math.min(MAX_TITLE_CHARS, MAX_TOTAL_TITLE_CHARS - titleChars),
                "chapter title");
    }

    void addParagraph(String id, String paragraphId, String text) throws IOException {
        if (text == null || text.isEmpty()) return;
        id = required(id, "chapter id");
        paragraphId = required(paragraphId, "paragraph id");
        int separator = textLength == 0 ? 0 : 1;
        if ((long) textLength + separator + text.length() > maxTextChars) {
            throw new IOException("Extracted text exceeds safety limit of " + maxTextChars + " characters");
        }
        if (paragraphCount >= maxParagraphs) {
            throw new IOException("Extracted paragraph count exceeds safety limit of " + maxParagraphs);
        }
        if (chapterText == null) {
            chapterId = id;
            chapterStart = textLength + separator;
            chapterText = new StringBuilder();
            anchors = new ArrayList<>();
        } else {
            if (!chapterId.equals(id)) throw new IllegalStateException("Finish the active chapter first");
            chapterText.append('\n');
        }
        long start = (long) textLength + separator;
        chapterText.append(text);
        textLength += separator + text.length();
        anchors.add(new ContentAnchor(id + ":" + paragraphId, id, paragraphId, start, textLength));
        paragraphCount++;
    }

    boolean hasPendingChapter() { return chapterText != null; }

    void finishChapter(String title) throws IOException {
        if (chapterText == null) return;
        title = title == null ? "" : title.trim();
        if (title.length() > MAX_TITLE_CHARS) {
            throw new IOException("Extracted chapter title exceeds safety limit of " + MAX_TITLE_CHARS + " characters");
        }
        if (title.length() > MAX_TOTAL_TITLE_CHARS - titleChars) {
            throw new IOException("Extracted chapter titles exceed total safety limit of " + MAX_TOTAL_TITLE_CHARS + " characters");
        }
        chapters.add(new ExtractedChapter(chapterId, title, chapterStart, textLength,
                chapterText.toString(), anchors));
        titleChars += title.length();
        chapterId = null;
        chapterText = null;
        anchors = null;
    }

    ExtractedContent build() {
        if (hasPendingChapter()) throw new IllegalStateException("Finish the active chapter first");
        String text = switch (chapters.size()) {
            case 0 -> "";
            case 1 -> chapters.getFirst().text();
            default -> String.join("\n", chapters.stream().map(ExtractedChapter::text).toList());
        };
        return new ExtractedContent(sourceId, format, text, chapters);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
