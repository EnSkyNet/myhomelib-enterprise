package com.myhomelibcorp.benchmark;

import com.myhomelibcorp.infrastructure.importengine.InpxImportPipeline;
import com.myhomelibcorp.infrastructure.profiling.PerformanceProfiler;
import com.myhomelibcorp.infrastructure.monitoring.MemoryMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportBenchmark {

    private final InpxImportPipeline pipeline;
    private final PerformanceProfiler profiler;
    private final MemoryMonitor memoryMonitor;

    public void runBenchmark(Path file, int batchSize) {
        log.info("🚀 Starting benchmark for: {}", file);

        profiler.start("import");
        memoryMonitor.startMonitoring(1000);

        long startTime = System.currentTimeMillis();
        long count = pipeline.importFile(file, batchSize);
        long duration = System.currentTimeMillis() - startTime;

        memoryMonitor.stopMonitoring();
        profiler.stop("import");
        profiler.logSummary();

        log.info("📊 Benchmark results:");
        log.info("  File: {}", file);
        log.info("  Books: {}", count);
        log.info("  Duration: {} ms", duration);
        log.info("  Books/sec: {}", count / (duration / 1000.0));
        log.info("  Batch size: {}", batchSize);
    }
}