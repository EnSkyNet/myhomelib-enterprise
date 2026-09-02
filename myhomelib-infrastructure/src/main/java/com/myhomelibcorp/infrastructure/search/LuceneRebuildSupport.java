package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.search.SearchIndexPerformanceReport;
import com.myhomelibcorp.application.search.SearchIndexProgress;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.store.Directory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Pure rebuild cancellation/progress/telemetry helpers kept out of Lucene orchestration. */
@Slf4j
final class LuceneRebuildSupport {
    private LuceneRebuildSupport() {}

    static void checkCancelled(AtomicBoolean cancelFlag) {
        if ((cancelFlag != null && cancelFlag.get()) || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    static void notifyProgress(Consumer<SearchIndexProgress> listener, long processed, long total) {
        if (listener == null) return;
        try {
            listener.accept(new SearchIndexProgress(processed, total));
        } catch (RuntimeException callbackFailure) {
            log.debug("Search-index progress listener failed: {}", callbackFailure.toString());
        }
    }

    static SearchIndexPerformanceReport report(
            Instant startedAt, String outcome, long indexed, long total, long startedNanos,
            long dbReadNanos, long documentBuildNanos, long luceneWriteNanos,
            long mergeWaitNanos, long commitNanos, long peakHeap,
            long gcCollectionsBefore, long gcTimeBefore, Directory directory) {
        long totalTime = LuceneIndexMetrics.elapsedMs(startedNanos);
        return new SearchIndexPerformanceReport(
                startedAt, outcome, indexed, total, totalTime,
                indexed * 1000.0 / Math.max(1, totalTime),
                LuceneIndexMetrics.nanosToMs(dbReadNanos),
                LuceneIndexMetrics.nanosToMs(documentBuildNanos),
                LuceneIndexMetrics.nanosToMs(luceneWriteNanos),
                LuceneIndexMetrics.nanosToMs(mergeWaitNanos),
                LuceneIndexMetrics.nanosToMs(commitNanos),
                Math.max(peakHeap, LuceneIndexMetrics.usedHeapBytes()),
                Math.max(0, LuceneIndexMetrics.totalGcCollections() - gcCollectionsBefore),
                Math.max(0, LuceneIndexMetrics.totalGcTimeMs() - gcTimeBefore),
                LuceneIndexMetrics.indexSizeBytes(directory), LuceneIndexMetrics.segmentCount(directory));
    }

    static final class CancelledException extends RuntimeException {
        CancelledException() { super("Індексацію скасовано"); }
    }
}
