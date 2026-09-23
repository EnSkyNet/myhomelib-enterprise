package com.myhomelibcorp.domain.model.sync;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Value
@Builder
public class SyncResult {
    @Builder.Default
    int added = 0;

    @Builder.Default
    int updated = 0;

    @Builder.Default
    int deleted = 0;

    @Builder.Default
    int skipped = 0;

    @Builder.Default
    int errors = 0;

    @Builder.Default
    List<String> errorMessages = new ArrayList<>();

    @Builder.Default
    LocalDateTime startTime = LocalDateTime.now();

    @Builder.Default
    LocalDateTime endTime = LocalDateTime.now();

    public Duration getDuration() {
        return Duration.between(startTime, endTime);
    }

    public int getTotalProcessed() {
        return added + updated + deleted + skipped;
    }

    /** Diagnostic, UI-neutral summary. User-facing formatting belongs to the presentation layer. */
    public String getSummary() {
        return String.format(java.util.Locale.ROOT,
                "added=%d, updated=%d, deleted=%d, skipped=%d, errors=%d, durationMs=%d",
                added, updated, deleted, skipped, errors, getDuration().toMillis());
    }
}