package com.myhomelibcorp.reader.core.document;

import com.myhomelibcorp.reader.api.*;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class CompactReaderDocument implements ReaderDocument {

    private final BookMetadata metadata;
    private final List<ChapterIndex> chapters;
    private final ResourceRepository resources;
    private final TextStorage text;
    private final TableOfContents toc;
    private final long totalTextLength;

    @Override
    public BookMetadata metadata() {
        return metadata;
    }

    @Override
    public List<ChapterIndex> chapters() {
        return chapters;
    }

    @Override
    public ResourceRepository resources() {
        return resources;
    }

    @Override
    public TextStorage text() {
        return text;
    }

    @Override
    public TableOfContents toc() {
        return toc;
    }

    @Override
    public long totalTextLength() {
        return totalTextLength > 0 ? totalTextLength : (text != null ? text.length() : 0);
    }

    @Override
    public boolean isEmpty() {
        return metadata == null ||
                metadata.title() == null ||
                metadata.title().isBlank() ||
                text == null ||
                text.length() == 0;
    }

    @Override
    public int chapterIndexAt(long textOffset) {
        if (chapters == null || chapters.isEmpty()) {
            return 0;
        }
        // Chapters are emitted in document order. Use the nearest chapter whose
        // start offset is <= the requested position; this is O(log N) and also
        // handles separator/gap offsets more correctly than falling back to the
        // final chapter.
        int lo = 0;
        int hi = chapters.size() - 1;
        int best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (chapters.get(mid).startOffset() <= textOffset) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    @Override
    public ChapterIndex chapter(int index) {
        if (chapters == null || index < 0 || index >= chapters.size()) {
            return null;
        }
        return chapters.get(index);
    }

    @Override
    public String toString() {
        return "CompactReaderDocument{" +
                "title='" + (metadata != null ? metadata.title() : "null") + '\'' +
                ", chapters=" + (chapters != null ? chapters.size() : 0) +
                ", textLength=" + (text != null ? text.length() : 0) +
                '}';
    }
}