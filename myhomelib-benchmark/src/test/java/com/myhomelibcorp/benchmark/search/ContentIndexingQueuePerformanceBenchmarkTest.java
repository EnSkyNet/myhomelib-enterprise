package com.myhomelibcorp.benchmark.search;

import com.myhomelibcorp.application.content.indexing.*;
import com.myhomelibcorp.application.port.out.contentindexing.ContentIndexQueueCheckpointPort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in benchmark proving that Balanced background indexing control calls remain short and CPU concurrency is bounded. */
class ContentIndexingQueuePerformanceBenchmarkTest {
    @Test
    @EnabledIfSystemProperty(named = "myhomelib.indexQueueBenchmark", matches = "true")
    void balancedQueueKeepsCallerLatencyLowAndWorkersBounded() throws Exception {
        int taskCount = Integer.getInteger("myhomelib.indexQueueBenchmark.tasks", 300);
        Path output = Path.of(System.getProperty("myhomelib.indexQueueBenchmark.output",
                "target/indexing-queue-benchmark.csv")).toAbsolutePath();
        MemorySettings rawSettings = new MemorySettings();
        IndexingPerformanceSettingsService settings = new IndexingPerformanceSettingsService(rawSettings);
        settings.save(new IndexingPerformanceSettings(IndexingResourceProfile.BALANCED, false));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        var queue = new ContentIndexingQueueService((task, control) -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try { LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2)); }
            finally { active.decrementAndGet(); }
            return ContentIndexingOutcome.completed();
        }, new MemoryCheckpoint(), settings, () -> false);

        try {
            long[] latencies = new long[taskCount];
            long wallStarted = System.nanoTime();
            for (int i = 0; i < taskCount; i++) {
                ContentIndexingTask task = new ContentIndexingTask("bench-" + i, "bench", "book-" + i, "artifact-" + i,
                        "/benchmark/" + i + ".txt", "txt", ContentIndexingPriority.NORMAL, Instant.now());
                long started = System.nanoTime();
                queue.enqueue(task);
                latencies[i] = System.nanoTime() - started;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (queue.snapshot().completed() < taskCount && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            long totalMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - wallStarted);
            Arrays.sort(latencies);
            long p95Micros = TimeUnit.NANOSECONDS.toMicros(latencies[Math.min(latencies.length - 1, (int)Math.ceil(latencies.length * 0.95) - 1)]);
            long maxMicros = TimeUnit.NANOSECONDS.toMicros(latencies[latencies.length - 1]);
            int cpu = Math.max(1, Runtime.getRuntime().availableProcessors());
            int workerLimit = IndexingResourceProfile.BALANCED.workerThreads(cpu);

            Files.createDirectories(output.getParent());
            String csv = "timestamp,tasks,cpu,profile,worker_limit,max_active,p95_enqueue_us,max_enqueue_us,total_ms,completed\n"
                    + Instant.now() + "," + taskCount + "," + cpu + ",BALANCED," + workerLimit + "," + maxActive.get()
                    + "," + p95Micros + "," + maxMicros + "," + totalMillis + "," + queue.snapshot().completed() + "\n";
            Files.writeString(output, csv, StandardCharsets.UTF_8);

            assertThat(queue.snapshot().completed()).isEqualTo(taskCount);
            assertThat(maxActive.get()).isBetween(1, workerLimit);
            assertThat(p95Micros).isLessThan(50_000L);
        } finally {
            queue.destroy();
        }
    }

    static final class MemoryCheckpoint implements ContentIndexQueueCheckpointPort {
        final Map<String, ContentIndexQueueCheckpoint> states = new ConcurrentHashMap<>();
        @Override public Optional<ContentIndexQueueCheckpoint> load(String collectionId) { return Optional.ofNullable(states.get(collectionId)); }
        @Override public void save(ContentIndexQueueCheckpoint checkpoint) { states.put(checkpoint.collectionId(), checkpoint); }
        @Override public void clear(String collectionId) { states.remove(collectionId); }
    }

    static final class MemorySettings implements ApplicationSettingsPort {
        final Map<String, String> values = new ConcurrentHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { if (value == null) values.remove(key); else values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }
}
