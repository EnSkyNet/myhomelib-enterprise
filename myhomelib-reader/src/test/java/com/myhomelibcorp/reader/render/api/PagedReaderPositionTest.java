package com.myhomelibcorp.reader.render.api;

import com.myhomelibcorp.reader.api.ReaderPosition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PagedReaderPositionTest {
    @Test
    void persistsAndRestoresPageUsingSharedReaderPositionContract() {
        ReaderPosition stored = PagedReaderPosition.toReaderPosition(17);

        assertThat(stored.chapterIndex()).isEqualTo(17);
        assertThat(stored.textOffset()).isEqualTo(17);
        assertThat(PagedReaderPosition.restorePageIndex(stored, 100)).isEqualTo(17);
    }

    @Test
    void restoreClampsMissingNegativeAndOutOfRangePositions() {
        assertThat(PagedReaderPosition.restorePageIndex(null, 10)).isZero();
        assertThat(PagedReaderPosition.restorePageIndex(new ReaderPosition(0, -5, 0, 0), 10)).isZero();
        assertThat(PagedReaderPosition.restorePageIndex(new ReaderPosition(0, 999, 0, 0), 10)).isEqualTo(9);
        assertThat(PagedReaderPosition.restorePageIndex(new ReaderPosition(0, 5, 0, 0), 0)).isZero();
    }

    @Test
    void progressUsesFirstAndLastPageAsZeroAndHundredPercent() {
        assertThat(PagedReaderPosition.progressPercent(0, 10)).isEqualTo(0.0);
        assertThat(PagedReaderPosition.progressPercent(9, 10)).isEqualTo(100.0);
        assertThat(PagedReaderPosition.progressPercent(5, 11)).isEqualTo(50.0);
        assertThat(PagedReaderPosition.progressPercent(4, 1)).isEqualTo(0.0);
    }
}
