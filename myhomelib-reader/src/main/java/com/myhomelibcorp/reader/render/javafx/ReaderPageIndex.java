package com.myhomelibcorp.reader.render.javafx;

import java.util.Arrays;

/**
 * Compact canonical page-boundary index. Stores only page start text offsets,
 * never PageLayout/paragraph/line objects. One long per page keeps even very
 * large books bounded compared with retaining rendered pages.
 */
public final class ReaderPageIndex {
    private long[] starts = new long[256];
    private int size;
    private boolean complete;

    public void reset() {
        size = 0;
        complete = false;
    }

    public void appendStart(long offset) {
        long safe = Math.max(0, offset);
        if (size > 0 && safe <= starts[size - 1]) return;
        ensureCapacity(size + 1);
        starts[size++] = safe;
    }

    public void markComplete() { complete = true; }

    public boolean isComplete() { return complete; }

    public int indexedPages() { return size; }

    public int totalPages() { return complete ? Math.max(1, size) : 0; }

    public long lastStart() { return size == 0 ? -1 : starts[size - 1]; }

    /** Returns 0 while the canonical page containing offset has not been indexed yet. */
    public int pageForOffset(long offset) {
        if (size == 0) return 0;
        long safe = Math.max(0, offset);
        if (!complete && safe > starts[size - 1]) return 0;
        int low = 0;
        int high = size - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (starts[mid] <= safe) low = mid + 1;
            else high = mid - 1;
        }
        return Math.max(1, high + 1);
    }

    public boolean hasPage(int oneBasedPage) {
        return oneBasedPage >= 1 && oneBasedPage <= size;
    }

    public long offsetForPage(int oneBasedPage) {
        if (!hasPage(oneBasedPage)) throw new IllegalArgumentException("Page is not indexed: " + oneBasedPage);
        return starts[oneBasedPage - 1];
    }

    private void ensureCapacity(int needed) {
        if (needed <= starts.length) return;
        int next = Math.max(needed, starts.length + (starts.length >>> 1));
        starts = Arrays.copyOf(starts, next);
    }
}
