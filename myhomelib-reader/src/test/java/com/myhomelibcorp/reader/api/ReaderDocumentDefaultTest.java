package com.myhomelibcorp.reader.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderDocumentDefaultTest {
    @Test
    void emptyChapterMetadataHasSafeDefaultNavigation() {
        ReaderDocument document = document(List.of());

        assertThat(document.chapterIndexAt(0)).isZero();
        assertThat(document.chapter(0)).isNull();
    }

    @Test
    void nullChapterMetadataHasSafeDefaultNavigation() {
        ReaderDocument document = document(null);

        assertThat(document.chapterIndexAt(42)).isZero();
        assertThat(document.chapter(0)).isNull();
    }



    @Test
    void documentWithoutTextIsEmptyEvenWhenMetadataHasTitle() {
        ReaderDocument document = new ReaderDocument() {
            @Override public BookMetadata metadata() {
                return new BookMetadata("id", "Title", List.of(), "en", null, null, List.of(), "", "", "", null, 0);
            }
            @Override public List<ChapterIndex> chapters() { return List.of(); }
            @Override public ResourceRepository resources() { return null; }
            @Override public TextStorage text() { return null; }
            @Override public TableOfContents toc() { return null; }
            @Override public long totalTextLength() { return 0; }
        };

        assertThat(document.isEmpty()).isTrue();
    }

    @Test
    void offsetsBeforeFirstChapterStayAtFirstChapter() {
        ReaderDocument document = document(List.of(
                new ChapterIndex("c1", "One", 10, 20, 1),
                new ChapterIndex("c2", "Two", 30, 40, 1)
        ));

        assertThat(document.chapterIndexAt(0)).isZero();
        assertThat(document.chapterIndexAt(9)).isZero();
    }

    @Test
    void offsetsInChapterGapsStayWithNearestPreviousChapter() {
        ReaderDocument document = document(List.of(
                new ChapterIndex("c1", "One", 0, 10, 1),
                new ChapterIndex("c2", "Two", 20, 30, 1),
                new ChapterIndex("c3", "Three", 40, 50, 1)
        ));

        assertThat(document.chapterIndexAt(15)).isZero();
        assertThat(document.chapterIndexAt(35)).isEqualTo(1);
    }

    @Test
    void offsetsAfterLastChapterStayAtLastChapter() {
        ReaderDocument document = document(List.of(
                new ChapterIndex("c1", "One", 0, 10, 1),
                new ChapterIndex("c2", "Two", 20, 30, 1)
        ));

        assertThat(document.chapterIndexAt(30)).isEqualTo(1);
        assertThat(document.chapterIndexAt(999)).isEqualTo(1);
    }

    private static ReaderDocument document(List<ChapterIndex> chapters) {
        return new ReaderDocument() {
            @Override public BookMetadata metadata() { return null; }
            @Override public List<ChapterIndex> chapters() { return chapters; }
            @Override public ResourceRepository resources() { return null; }
            @Override public TextStorage text() { return null; }
            @Override public TableOfContents toc() { return null; }
            @Override public long totalTextLength() { return 0; }
        };
    }
}
