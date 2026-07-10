package com.myhomelibcorp.infrastructure.profiling;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class PerformanceProfiler {

    private final Map<String, Long> startTimes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> totalTimes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    public void start(String operation) {
        startTimes.put(operation, System.nanoTime());
    }

    public void stop(String operation) {
        Long start = startTimes.remove(operation);
        if (start != null) {
            long elapsed = System.nanoTime() - start;
            totalTimes.computeIfAbsent(operation, k -> new AtomicLong()).addAndGet(elapsed);
            counts.computeIfAbsent(operation, k -> new AtomicLong()).incrementAndGet();
            log.debug("{}: {} ms", operation, elapsed / 1_000_000);
        }
    }

    public void logSummary() {
        StringBuilder sb = new StringBuilder("📊 Performance Summary:\n");
        for (String op : totalTimes.keySet()) {
            long total = totalTimes.get(op).get();
            long count = counts.get(op).get();
            double avg = count > 0 ? (double) total / count / 1_000_000 : 0;
            sb.append(String.format("  %-30s: total=%,.2f ms, avg=%,.2f ms, count=%,d%n",
                    op, total / 1_000_000.0, avg, count));
        }
        log.info(sb.toString());
    }

    public void reset() {
        totalTimes.clear();
        counts.clear();
        startTimes.clear();
    }
}