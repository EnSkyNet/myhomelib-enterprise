package com.myhomelibcorp.application.webreader;

import java.time.Instant;

/** Browser progress update. Client sequence is monotonic per browser and timestamp is used for stale-update rejection. */
public record WebProgressUpdate(
        int chapterIndex,
        long absoluteOffset,
        Instant clientTimestamp,
        long clientSequence) {
    public WebProgressUpdate {
        if (chapterIndex < 0) throw new IllegalArgumentException("chapterIndex must be >= 0");
        if (absoluteOffset < 0) throw new IllegalArgumentException("absoluteOffset must be >= 0");
        if (clientTimestamp == null) throw new IllegalArgumentException("clientTimestamp is required");
        if (clientSequence < 0) throw new IllegalArgumentException("clientSequence must be >= 0");
    }
}
