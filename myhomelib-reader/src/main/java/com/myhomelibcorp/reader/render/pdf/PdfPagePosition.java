package com.myhomelibcorp.reader.render.pdf;

import com.myhomelibcorp.reader.api.ReaderPosition;

/** Maps zero-based PDF page indexes to the existing Reader position persistence contract. */
final class PdfPagePosition {
    private PdfPagePosition() { }

    static ReaderPosition toReaderPosition(int pageIndex) {
        int safe = Math.max(0, pageIndex);
        return new ReaderPosition(safe, safe, 0, 0);
    }

    static int restorePageIndex(ReaderPosition savedPosition, int pageCount) {
        if (pageCount <= 0 || savedPosition == null) return 0;
        long storedPage = savedPosition.textOffset();
        if (storedPage < 0) return 0;
        return (int) Math.min(pageCount - 1L, storedPage);
    }

    static double progressPercent(int pageIndex, int pageCount) {
        if (pageCount <= 1) return 0.0;
        int safePage = Math.max(0, Math.min(pageCount - 1, pageIndex));
        return safePage * 100.0 / (pageCount - 1.0);
    }
}
