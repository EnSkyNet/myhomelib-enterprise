package com.myhomelibcorp.reader.render.pdf;

import com.myhomelibcorp.reader.api.ReaderPosition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfPagePositionTest {
    @Test
    void persistsAndRestoresPageUsingExistingReaderPositionContract() {
        ReaderPosition stored = PdfPagePosition.toReaderPosition(17);

        assertThat(stored.chapterIndex()).isEqualTo(17);
        assertThat(stored.textOffset()).isEqualTo(17);
        assertThat(PdfPagePosition.restorePageIndex(stored, 100)).isEqualTo(17);
    }

    @Test
    void restoreClampsOldOrOutOfRangePositions() {
        assertThat(PdfPagePosition.restorePageIndex(null, 10)).isZero();
        assertThat(PdfPagePosition.restorePageIndex(new ReaderPosition(0, -5, 0, 0), 10)).isZero();
        assertThat(PdfPagePosition.restorePageIndex(new ReaderPosition(0, 999, 0, 0), 10)).isEqualTo(9);
        assertThat(PdfPagePosition.restorePageIndex(new ReaderPosition(0, 5, 0, 0), 0)).isZero();
    }

    @Test
    void progressUsesFirstAndLastPageAsZeroAndHundredPercent() {
        assertThat(PdfPagePosition.progressPercent(0, 10)).isEqualTo(0.0);
        assertThat(PdfPagePosition.progressPercent(9, 10)).isEqualTo(100.0);
        assertThat(PdfPagePosition.progressPercent(4, 1)).isEqualTo(0.0);
    }
}
