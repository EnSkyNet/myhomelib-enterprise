package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.config.DataSourceConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in, read-only profile for backup plus full pre-update checkpoint integrity_check on a
 * production-sized catalogue. Run against a closed Windows Portable database to capture the
 * real DB/WAL/SHM footprint and checkpoint CPU/heap cost without exporting book metadata.
 */
class RealRecoveryCheckpointPerformanceProbeTest {
    private static final long EXTRA_CHECKPOINT_ALLOWANCE_BYTES = 64L * 1024L * 1024L;

    @Test
    @EnabledIfSystemProperty(named = "mhl.real.db", matches = ".+")
    void createsRecoveryCheckpointFromRealDatabase() throws Exception {
        Path db = Path.of(System.getProperty("mhl.real.db")).toAbsolutePath().normalize();
        Path checkpoint = Path.of(System.getProperty("mhl.real.checkpoint",
                Path.of(System.getProperty("java.io.tmpdir"), "myhomelib-real-recovery-checkpoint.db").toString()))
                .toAbsolutePath().normalize();
        Path profile = Path.of(System.getProperty("mhl.real.profile",
                "target/real-recovery-checkpoint-profile.json")).toAbsolutePath().normalize();
        assertThat(Files.isRegularFile(db, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(checkpoint).isNotEqualTo(db).isNotEqualTo(profile);
        assertThat(profile).isNotEqualTo(db);
        Files.createDirectories(checkpoint.getParent());
        Files.createDirectories(profile.getParent());
        assertThat(checkpoint).doesNotExist();

        long sourceBytesBeforeOpen = Files.size(db);
        long walBytesBeforeOpen = fileSize(Path.of(db + "-wal"));
        long shmBytesBeforeOpen = fileSize(Path.of(db + "-shm"));
        long wallMs;
        long cpuMs;
        long sourceBytesAfter;
        long checkpointBytes;
        long walBytesAtStart;
        long shmBytesAtStart;
        long walBytesAfter;
        long shmBytesAfter;
        long heapBefore;
        long heapAfter;
        long peakHeap;
        long maxHeap = Runtime.getRuntime().maxMemory();
        long physicalMemory;
        long freePhysicalBefore;
        long freePhysicalAfter;
        String sqliteVersion;
        String journalMode;
        long busyTimeoutMs;
        long synchronous;
        long pageCount;
        long pageSize;
        long cacheSize;
        long mmapSize;
        long startedAtEpochMs = System.currentTimeMillis();

        try {
            try (HikariDataSource ds = new DataSourceConfig().createDataSourceForPath(db.toString());
                 Connection probeConnection = ds.getConnection()) {
                JdbcTemplate jdbc = new JdbcTemplate(ds);
                sqliteVersion = scalarText(probeConnection, "SELECT sqlite_version()");
                journalMode = scalarText(probeConnection, "PRAGMA journal_mode");
                busyTimeoutMs = scalarLong(probeConnection, "PRAGMA busy_timeout");
                synchronous = scalarLong(probeConnection, "PRAGMA synchronous");
                pageCount = scalarLong(probeConnection, "PRAGMA page_count");
                pageSize = scalarLong(probeConnection, "PRAGMA page_size");
                cacheSize = scalarLong(probeConnection, "PRAGMA cache_size");
                mmapSize = scalarLong(probeConnection, "PRAGMA mmap_size");

                walBytesAtStart = fileSize(Path.of(db + "-wal"));
                shmBytesAtStart = fileSize(Path.of(db + "-shm"));
                var os = operatingSystemBean();
                physicalMemory = os == null ? -1L : os.getTotalMemorySize();
                freePhysicalBefore = os == null ? -1L : os.getFreeMemorySize();
                heapBefore = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                resetPeakHeapUsage();
                long cpuBefore = processCpuTimeNanos(os);

                CollectionManager manager = mock(CollectionManager.class);
                when(manager.hasActiveCollection()).thenReturn(true);
                when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
                CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager);
                Collection collection = mock(Collection.class);
                when(collection.getId()).thenReturn("real-checkpoint-probe");
                when(manager.getCurrentCollection()).thenReturn(collection);

                long started = System.nanoTime();
                adapter.createRecoveryCheckpoint(collection, checkpoint);
                wallMs = (System.nanoTime() - started) / 1_000_000L;
                long cpuAfter = processCpuTimeNanos(os);
                cpuMs = cpuBefore < 0L || cpuAfter < cpuBefore ? -1L : (cpuAfter - cpuBefore) / 1_000_000L;
                heapAfter = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                peakHeap = peakHeapUsedBytes();
                freePhysicalAfter = os == null ? -1L : os.getFreeMemorySize();
                sourceBytesAfter = Files.size(db);
                checkpointBytes = Files.size(checkpoint);
                walBytesAfter = fileSize(Path.of(db + "-wal"));
                shmBytesAfter = fileSize(Path.of(db + "-shm"));
            }

            String json = profileJson(db, startedAtEpochMs, sqliteVersion, journalMode, busyTimeoutMs,
                    synchronous, pageCount, pageSize, cacheSize, mmapSize,
                    sourceBytesBeforeOpen, sourceBytesAfter, walBytesBeforeOpen, shmBytesBeforeOpen,
                    walBytesAtStart, shmBytesAtStart, walBytesAfter, shmBytesAfter, checkpointBytes,
                    wallMs, cpuMs, heapBefore, heapAfter, peakHeap, maxHeap,
                    physicalMemory, freePhysicalBefore, freePhysicalAfter);
            Files.writeString(profile, json);
            System.out.println("REAL_CHECKPOINT_PROFILE_JSON=" + json.replace(System.lineSeparator(), ""));
            System.out.printf(Locale.ROOT,
                    "REAL_CHECKPOINT_PROFILE_RESULT wallMs=%d cpuMs=%d dbBytes=%d walBytes=%d shmBytes=%d checkpointBytes=%d peakHeapBytes=%d busyTimeoutMs=%d%n",
                    wallMs, cpuMs, sourceBytesAfter, walBytesAtStart, shmBytesAtStart,
                    checkpointBytes, peakHeap, busyTimeoutMs);

            assertThat(journalMode).isEqualToIgnoringCase("wal");
            assertThat(checkpointBytes).isGreaterThan(100L);
            assertThat(checkpointBytes).isLessThanOrEqualTo(
                    sourceBytesAfter + walBytesAtStart + EXTRA_CHECKPOINT_ALLOWANCE_BYTES);
        } finally {
            // The checkpoint contains the user's full catalogue. Do not leave a second copy behind.
            Files.deleteIfExists(checkpoint);
        }
    }

    private static String profileJson(
            Path db, long startedAtEpochMs, String sqliteVersion, String journalMode, long busyTimeoutMs,
            long synchronous, long pageCount, long pageSize, long cacheSize, long mmapSize,
            long sourceBytesBeforeOpen, long sourceBytesAfter, long walBytesBeforeOpen, long shmBytesBeforeOpen,
            long walBytesAtStart, long shmBytesAtStart, long walBytesAfter, long shmBytesAfter,
            long checkpointBytes, long wallMs, long cpuMs, long heapBefore, long heapAfter,
            long peakHeap, long maxHeap, long physicalMemory, long freePhysicalBefore, long freePhysicalAfter) {
        return """
                {
                  "schema": 1,
                  "startedAtEpochMs": %d,
                  "databaseFileName": "%s",
                  "osName": "%s",
                  "osVersion": "%s",
                  "osArchitecture": "%s",
                  "javaVersion": "%s",
                  "availableProcessors": %d,
                  "sqliteVersion": "%s",
                  "journalMode": "%s",
                  "busyTimeoutMs": %d,
                  "busyExceptionsObservedDuringCheckpoint": 0,
                  "busyEventCounterAvailable": false,
                  "synchronous": %d,
                  "pageCount": %d,
                  "pageSizeBytes": %d,
                  "cacheSizePragma": %d,
                  "mmapSizeBytes": %d,
                  "sourceDatabaseBytesBeforeOpen": %d,
                  "sourceDatabaseBytesAfter": %d,
                  "walBytesBeforeOpen": %d,
                  "shmBytesBeforeOpen": %d,
                  "walBytesAtCheckpointStart": %d,
                  "shmBytesAtCheckpointStart": %d,
                  "walBytesAfterCheckpoint": %d,
                  "shmBytesAfterCheckpoint": %d,
                  "checkpointBytes": %d,
                  "checkpointWallMs": %d,
                  "checkpointProcessCpuMs": %d,
                  "heapUsedBeforeBytes": %d,
                  "heapUsedAfterBytes": %d,
                  "peakHeapUsedBytesSinceProbeStart": %d,
                  "maxHeapBytes": %d,
                  "totalPhysicalMemoryBytes": %d,
                  "freePhysicalMemoryBeforeBytes": %d,
                  "freePhysicalMemoryAfterBytes": %d,
                  "notes": "No SQLite BUSY exception escaped the checkpoint operation. Xerial JDBC does not expose an aggregate busy-handler retry counter; busyTimeoutMs is the configured per-connection wait limit. The checkpoint is deleted after measurement."
                }
                """.formatted(startedAtEpochMs, jsonEscape(db.getFileName().toString()),
                jsonEscape(System.getProperty("os.name", "")), jsonEscape(System.getProperty("os.version", "")),
                jsonEscape(System.getProperty("os.arch", "")), jsonEscape(System.getProperty("java.version", "")),
                Runtime.getRuntime().availableProcessors(), jsonEscape(sqliteVersion), jsonEscape(journalMode),
                busyTimeoutMs, synchronous, pageCount, pageSize, cacheSize, mmapSize,
                sourceBytesBeforeOpen, sourceBytesAfter, walBytesBeforeOpen, shmBytesBeforeOpen,
                walBytesAtStart, shmBytesAtStart, walBytesAfter, shmBytesAfter, checkpointBytes,
                wallMs, cpuMs, heapBefore, heapAfter, peakHeap, maxHeap, physicalMemory,
                freePhysicalBefore, freePhysicalAfter);
    }

    private static String scalarText(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("SQLite returned no value for " + sql);
            return result.getString(1);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("SQLite returned no value for " + sql);
            return result.getLong(1);
        }
    }

    private static long fileSize(Path path) throws Exception {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? Files.size(path) : 0L;
    }

    private static com.sun.management.OperatingSystemMXBean operatingSystemBean() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean sun ? sun : null;
    }

    private static long processCpuTimeNanos(com.sun.management.OperatingSystemMXBean bean) {
        return bean == null ? -1L : bean.getProcessCpuTime();
    }

    private static void resetPeakHeapUsage() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) continue;
            try {
                pool.resetPeakUsage();
            } catch (UnsupportedOperationException ignored) {
                // A VM may expose a heap pool without resettable peak statistics.
            }
        }
    }

    private static long peakHeapUsedBytes() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .map(MemoryPoolMXBean::getPeakUsage)
                .filter(usage -> usage != null)
                .mapToLong(usage -> usage.getUsed())
                .sum();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
