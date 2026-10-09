package com.myhomelibcorp.reader.api;

import java.util.List;

public interface ReaderDocument {

    BookMetadata metadata();

    List<ChapterIndex> chapters();

    ResourceRepository resources();

    TextStorage text();

    TableOfContents toc();

    long totalTextLength();

    default boolean isEmpty() {
        BookMetadata metadata = metadata();
        TextStorage text = text();
        return metadata == null
                || metadata.title() == null
                || metadata.title().isBlank()
                || text == null
                || text.length() <= 0;
    }

    default int chapterIndexAt(long textOffset) {
        List<ChapterIndex> chapters = chapters();
        if (chapters == null || chapters.isEmpty()) {
            return 0;
        }
        int best = 0;
        for (int i = 0; i < chapters.size(); i++) {
            ChapterIndex ch = chapters.get(i);
            if (ch == null) continue;
            if (textOffset < ch.startOffset()) break;
            best = i;
            if (textOffset < ch.endOffset()) return i;
        }
        return best;
    }

    default ChapterIndex chapter(int index) {
        List<ChapterIndex> chapters = chapters();
        if (chapters == null || index < 0 || index >= chapters.size()) {
            return null;
        }
        return chapters.get(index);
    }
}