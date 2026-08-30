package com.myhomelibcorp.infrastructure.search;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;

/** Cheap performance telemetry helpers kept outside search orchestration. */
@Slf4j
final class LuceneIndexMetrics {
    private LuceneIndexMetrics() { }

    static long indexSizeBytes(Directory directory) {
        if (!(directory instanceof FSDirectory fsDirectory)) return -1L;
        try (var paths = Files.walk(fsDirectory.getDirectory())) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); } catch (IOException ignored) { return 0L; }
            }).sum();
        } catch (IOException e) {
            log.debug("Cannot calculate Lucene index size: {}", e.toString());
            return -1L;
        }
    }

    static int segmentCount(Directory directory) {
        try {
            if (!DirectoryReader.indexExists(directory)) return 0;
            try (DirectoryReader reader = DirectoryReader.open(directory)) { return reader.leaves().size(); }
        } catch (IOException e) {
            log.debug("Cannot read Lucene segment count: {}", e.toString());
            return -1;
        }
    }

    static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
    }

    static long totalGcCollections() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> bean.getCollectionCount()).filter(value -> value >= 0).sum();
    }

    static long totalGcTimeMs() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> bean.getCollectionTime()).filter(value -> value >= 0).sum();
    }

    static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    static long nanosToMs(long nanos) { return Math.max(0L, nanos / 1_000_000L); }
}
