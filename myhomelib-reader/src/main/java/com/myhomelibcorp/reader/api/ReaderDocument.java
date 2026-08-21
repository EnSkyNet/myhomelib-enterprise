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
        return metadata() == null || metadata().title() == null || metadata().title().isBlank();
    }

    default int chapterIndexAt(long textOffset) {
        List<ChapterIndex> chapters = chapters();
        for (int i = 0; i < chapters.size(); i++) {
            ChapterIndex ch = chapters.get(i);
            if (textOffset >= ch.startOffset() && textOffset < ch.endOffset()) {
                return i;
            }
        }
        return chapters.size() - 1;
    }

    default ChapterIndex chapter(int index) {
        List<ChapterIndex> chapters = chapters();
        if (index < 0 || index >= chapters.size()) {
            return null;
        }
        return chapters.get(index);
    }
}