package com.myhomelibcorp.reader.render.api;

import com.myhomelibcorp.reader.api.ReaderPosition;

/** Shared persistence mapping for page-addressed formats such as PDF and comics. */
public final class PagedReaderPosition {
    private PagedReaderPosition() { }

    public static ReaderPosition toReaderPosition(int pageIndex) {
        int safe = Math.max(0, pageIndex);
        return new ReaderPosition(safe, safe, 0, 0);
    }

    public static int restorePageIndex(ReaderPosition savedPosition, int pageCount) {
        if (pageCount <= 0 || savedPosition == null) return 0;
        long storedPage = savedPosition.textOffset();
        if (storedPage < 0) return 0;
        return (int) Math.min(pageCount - 1L, storedPage);
    }

    public static double progressPercent(int pageIndex, int pageCount) {
        if (pageCount <= 1) return 0.0;
        int safePage = Math.max(0, Math.min(pageCount - 1, pageIndex));
        return safePage * 100.0 / (pageCount - 1.0);
    }
}
