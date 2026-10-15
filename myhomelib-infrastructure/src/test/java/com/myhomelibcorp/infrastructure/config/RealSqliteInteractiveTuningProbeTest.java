package com.myhomelibcorp.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in same-database A/B probe for interactive SQLite/Hikari memory tuning.
 *
 * <p>This deliberately has no pass/fail latency threshold: the released defaults remain unchanged
 * unless the same-machine evidence shows a repeatable memory win without a meaningful latency
 * regression. Run separate JVMs for each profile so max RSS can be compared with /usr/bin/time.</p>
 */
class RealSqliteInteractiveTuningProbeTest {
    @Test
    @EnabledIfSystemProperty(named = "mhl.real.db", matches = ".+")
    void profilesInteractiveReadsOnTheSameRealDatabase() throws Exception {
        Path db = Path.of(System.getProperty("mhl.real.db")).toAbsolutePath().normalize();
        assertThat(db).isRegularFile();

        int poolSize = Integer.getInteger("mhl.ab.poolSize", DataSourceConfig.INTERACTIVE_MAX_POOL_SIZE);
        int cacheSizeKiB = Integer.getInteger("mhl.ab.cacheSizeKiB", DataSourceConfig.INTERACTIVE_CACHE_SIZE_KIB);
        long mmapSizeBytes = Long.getLong("mhl.ab.mmapSizeBytes", DataSourceConfig.INTERACTIVE_MMAP_SIZE_BYTES);
        int rounds = Integer.getInteger("mhl.ab.rounds", 7);
        assertThat(rounds).isBetween(3, 50);

        var profile = new DataSourceConfig.InteractiveProfile(poolSize, cacheSizeKiB, mmapSizeBytes);
        forceGc();
        long heapBefore = usedHeapBytes();
        long rssBefore = linuxRssBytes();
        long started = System.nanoTime();

        try (HikariDataSource ds = new DataSourceConfig(profile).createDataSourceForPath(db.toString())) {
            // Warm every physical connection so the cache/mmap profile is actually materialized.
            List<Connection> held = new ArrayList<>();
            try {
                for (int i = 0; i < poolSize; i++) {
                    Connection connection = ds.getConnection();
                    held.add(connection);
                    try (Statement statement = connection.createStatement();
                         ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM books WHERE deleted=0")) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getLong(1)).isGreaterThan(500_000L);
                    }
                }
                assertThat(ds.getHikariPoolMXBean().getTotalConnections()).isEqualTo(poolSize);
            } finally {
                for (Connection connection : held) connection.close();
            }

            double pageMedianMs = medianMillis("page", rounds, () -> scalar(ds, """
                    SELECT COUNT(*) FROM (
                      SELECT id,title FROM books WHERE deleted=0 ORDER BY title,id LIMIT 100
                    )
                    """));
            double authorMedianMs = medianMillis("author", rounds, () -> scalar(ds, """
                    SELECT COUNT(*) FROM (
                      SELECT a.id, COUNT(DISTINCT b.id)
                      FROM authors a
                      JOIN book_authors ba ON ba.author_id=a.id
                      JOIN books b ON b.id=ba.book_id
                      WHERE b.deleted=0 AND substr(lower(coalesce(a.last_name,a.first_name,a.middle_name,'')),1,1)='a'
                      GROUP BY a.id ORDER BY a.id LIMIT 250
                    )
                    """));
            double statisticsMedianMs = medianMillis("statistics", rounds, () -> scalar(ds, """
                    SELECT COUNT(*), SUM(CASE WHEN local=1 THEN 1 ELSE 0 END), SUM(file_size)
                    FROM books WHERE deleted=0
                    """));
            double parallelMedianMs = medianMillis("parallel4", rounds, () -> parallelRound(ds));

            forceGc();
            long heapAfter = usedHeapBytes();
            long rssAfter = linuxRssBytes();
            long wallMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.printf(Locale.ROOT,
                    "REAL_SQLITE_AB pool=%d cacheKiB=%d mmapBytes=%d rounds=%d pageMedianMs=%.3f authorMedianMs=%.3f statisticsMedianMs=%.3f parallel4MedianMs=%.3f heapBeforeBytes=%d heapAfterBytes=%d heapDeltaBytes=%d rssBeforeBytes=%d rssAfterBytes=%d rssDeltaBytes=%d wallMs=%d dbBytes=%d%n",
                    poolSize, cacheSizeKiB, mmapSizeBytes, rounds,
                    pageMedianMs, authorMedianMs, statisticsMedianMs, parallelMedianMs,
                    heapBefore, heapAfter, heapAfter - heapBefore,
                    rssBefore, rssAfter, rssBefore < 0 || rssAfter < 0 ? -1 : rssAfter - rssBefore,
                    wallMs, Files.size(db));
        }
    }

    private static long scalar(HikariDataSource ds, String sql) throws Exception {
        try (Connection connection = ds.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static long parallelRound(HikariDataSource ds) throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Long>> tasks = List.of(
                    () -> scalar(ds, "SELECT COUNT(*) FROM books WHERE deleted=0"),
                    () -> scalar(ds, "SELECT COUNT(*) FROM authors"),
                    () -> scalar(ds, "SELECT COUNT(*) FROM book_authors"),
                    () -> scalar(ds, "SELECT COUNT(*) FROM genres"));
            long started = System.nanoTime();
            var futures = executor.invokeAll(tasks);
            for (var future : futures) assertThat(future.get()).isPositive();
            return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static double medianMillis(String label, int rounds, ThrowingLongSupplier action) throws Exception {
        long[] micros = new long[rounds];
        for (int i = 0; i < rounds; i++) {
            long started = System.nanoTime();
            action.getAsLong();
            micros[i] = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
        }
        System.out.printf(Locale.ROOT, "REAL_SQLITE_SAMPLES query=%s micros=%s%n", label, java.util.Arrays.toString(micros));
        java.util.Arrays.sort(micros);
        return micros[micros.length / 2] / 1000.0;
    }

    private static void forceGc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(50).toNanos());
        }
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long linuxRssBytes() {
        Path status = Path.of("/proc/self/status");
        if (!Files.isRegularFile(status)) return -1L;
        try {
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) * 1024L;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            return -1L;
        }
        return -1L;
    }

    @FunctionalInterface
    private interface ThrowingLongSupplier {
        long getAsLong() throws Exception;
    }
}
