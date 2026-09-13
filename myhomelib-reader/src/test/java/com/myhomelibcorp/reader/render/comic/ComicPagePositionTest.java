package com.myhomelibcorp.reader.render.comic;

import com.myhomelibcorp.reader.api.ReaderPosition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComicPagePositionTest {
    @Test
    void restoresPageAndCalculatesProgress() {
        ReaderPosition saved = ComicPagePosition.toReaderPosition(7);
        assertThat(ComicPagePosition.restorePageIndex(saved, 20)).isEqualTo(7);
        assertThat(ComicPagePosition.restorePageIndex(saved, 4)).isEqualTo(3);
        assertThat(ComicPagePosition.progressPercent(5, 11)).isEqualTo(50.0);
    }
}
