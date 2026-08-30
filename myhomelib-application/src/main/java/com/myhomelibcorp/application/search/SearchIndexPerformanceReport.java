package com.myhomelibcorp.application.search;

import java.time.Instant;

/**
 * Reproducible full-index performance telemetry. Durations are wall-clock milliseconds;
 * mergeWaitMs is the final wait for outstanding Lucene merges before the single final commit.
 */
public record SearchIndexPerformanceReport(
        Instant startedAt,
        String outcome,
        long processedDocuments,
        long expectedDocuments,
        long totalDurationMs,
        double documentsPerSecond,
        long dbReadMs,
        long documentBuildMs,
        long luceneWriteMs,
        long mergeWaitMs,
        long commitMs,
        long peakHeapBytes,
        long gcCollectionsDelta,
        long gcTimeMsDelta,
        long indexSizeBytes,
        int segmentCount
) {
    public SearchIndexPerformanceReport {
        startedAt = startedAt == null ? Instant.now() : startedAt;
        outcome = outcome == null ? "UNKNOWN" : outcome;
    }
}
