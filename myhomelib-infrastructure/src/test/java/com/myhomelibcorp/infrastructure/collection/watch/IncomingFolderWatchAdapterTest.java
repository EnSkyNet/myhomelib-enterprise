package com.myhomelibcorp.infrastructure.collection.watch;

import com.myhomelibcorp.application.event.IncomingFolderFileReadyEvent;
import com.myhomelibcorp.shared.event.DomainEventPublisher;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class IncomingFolderWatchAdapterTest {
    @TempDir Path tempDir;
    private IncomingFolderWatchAdapter adapter;

    @AfterEach
    void close() {
        if (adapter != null) adapter.shutdown();
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 45)
    void bulkScanCommitsAllCandidatesBeforeAnyObserverCanRun() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path incoming = Files.createDirectory(tempDir.resolve("durable-batch-scan"));
        adapter.configure("c1", incoming, false, 3600, 3600);

        var schedulerField = IncomingFolderWatchAdapter.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        var scheduler = (java.util.concurrent.ScheduledThreadPoolExecutor) schedulerField.get(adapter);
        var started = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            scheduler.submit(() -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
        }
        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        int total = IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 44;
        try {
            for (int i = 0; i < total; i++) {
                Files.writeString(incoming.resolve("staged-" + i + ".txt"), "synthetic-" + i);
            }
            assertThat(adapter.scanNow("c1").status()).isEqualTo("SCAN_SCHEDULED:" + total);

            // The original implementation persisted only overflow paths here.
            // The first 256 pending timers had no durable row until their worker ran.
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM incoming_folder_file WHERE collection_id='c1'
                    """, Integer.class)).isEqualTo(total);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM incoming_folder_file
                     WHERE collection_id='c1' AND status='WAITING'
                    """, Integer.class)).isEqualTo(total);
            var pendingField = IncomingFolderWatchAdapter.class.getDeclaredField("pending");
            pendingField.setAccessible(true);
            var pending = (java.util.Map<?, ?>) pendingField.get(adapter);
            assertThat(pending.size()).isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES);
            assertThat(scheduler.getQueue().size())
                    .isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 1);

            // A repeat scan must not reset unchanged imported file identity.
            String imported = incoming.resolve("staged-299.txt").toAbsolutePath().normalize().toString();
            jdbc.update("""
                    UPDATE incoming_folder_file SET status='IMPORTED', fingerprint='already-imported'
                     WHERE collection_id='c1' AND file_path=?
                    """, imported);
            assertThat(adapter.scanNow("c1").status()).isEqualTo("SCAN_SCHEDULED:" + total);
            assertThat(jdbc.queryForObject("""
                    SELECT fingerprint FROM incoming_folder_file
                     WHERE collection_id='c1' AND file_path=? AND status='IMPORTED'
                    """, String.class, imported)).isEqualTo("already-imported");
        } finally {
            release.countDown();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 180)
    void tenThousandRealTestBooksStayInSqliteWithHardTimerBound() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path incoming = Files.createDirectory(tempDir.resolve("ten-thousand-test-books"));
        // Keep files in their one-hour stability window: no unbounded hashing or import.
        // Every book is a disposable tiny text fixture created in this JUnit temp directory.
        adapter.configure("c1", incoming, false, 3600, 3600);
        final int totalBooks = 10_001;
        for (int i = 0; i < totalBooks; i++) {
            Files.writeString(incoming.resolve(String.format(java.util.Locale.ROOT,
                    "test-book-%05d.txt", i)), "Synthetic content " + i);
        }

        assertThat(adapter.scanNow("c1").status()).isEqualTo("SCAN_SCHEDULED:" + totalBooks);
        var pendingField = IncomingFolderWatchAdapter.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        var schedulerField = IncomingFolderWatchAdapter.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        var pending = (java.util.Map<?, ?>) pendingField.get(adapter);
        var scheduler = (java.util.concurrent.ScheduledThreadPoolExecutor) schedulerField.get(adapter);
        assertThat(pending.size()).isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES);
        assertThat(scheduler.getQueue().size())
                .isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 1);
        await(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file WHERE collection_id='c1'
                """, Integer.class) == totalBooks, Duration.ofSeconds(30));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file
                 WHERE collection_id='c1' AND status='WAITING'
                """, Integer.class)).isEqualTo(totalBooks);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file
                 WHERE collection_id='c1' AND status='READY'
                """, Integer.class)).isZero();

        adapter.stopMonitoring("c1");
        assertThat(pending).isEmpty();
        assertThat(scheduler.getQueue()).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 120)
    void restartDrainsDurableOverflowEvenWhenAllOriginalTimersWereCancelled() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        Path incoming = Files.createDirectory(tempDir.resolve("restart-overflow"));
        // Before restart, files have a one-hour stability delay so that no book
        // can become READY while the adapter is being torn down.
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        adapter.configure("c1", incoming, false, 3600, 3600);
        int totalBooks = IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 104;
        for (int i = 0; i < totalBooks; i++) {
            Files.writeString(incoming.resolve("restart-book-" + i + ".txt"), "disposable-" + i);
        }
        assertThat(adapter.scanNow("c1").status()).isEqualTo("SCAN_SCHEDULED:" + totalBooks);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file WHERE collection_id='c1' AND status='WAITING'
                """, Integer.class)).isGreaterThan(0);

        adapter.shutdown();
        // Simulate persisted configuration for an enabled watch on the next process start.
        jdbc.update("""
                UPDATE incoming_folder_watch
                   SET enabled=1, debounce_seconds=1, stability_seconds=1
                 WHERE collection_id='c1'
                """);
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        adapter.startConfigured();

        await(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file WHERE collection_id='c1' AND status='READY'
                """, Integer.class) == totalBooks, Duration.ofSeconds(60));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file WHERE collection_id='c1'
                """, Integer.class)).isEqualTo(totalBooks);

        var pendingField = IncomingFolderWatchAdapter.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        var pending = (java.util.Map<?, ?>) pendingField.get(adapter);
        var schedulerField = IncomingFolderWatchAdapter.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        var scheduler = (java.util.concurrent.ScheduledThreadPoolExecutor) schedulerField.get(adapter);
        assertThat(pending.size()).isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES);
        assertThat(scheduler.getQueue().size())
                .isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 1);
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 120)
    void deleteRecreateStormAcrossOverflowDoesNotLoseSurvivingBooks() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path incoming = Files.createDirectory(tempDir.resolve("overflow-churn-books"));
        adapter.configure("c1", incoming, false, 2, 2);

        var schedulerField = IncomingFolderWatchAdapter.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        var scheduler = (java.util.concurrent.ScheduledThreadPoolExecutor) schedulerField.get(adapter);
        var pendingField = IncomingFolderWatchAdapter.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        var pending = (java.util.Map<?, ?>) pendingField.get(adapter);

        // Deterministically freeze both scheduler workers so no book can be READY
        // before the simulated file deletion and re-creation storm is complete.
        var workersStarted = new java.util.concurrent.CountDownLatch(2);
        var resumeWorkers = new java.util.concurrent.CountDownLatch(1);
        for (int worker = 0; worker < 2; worker++) {
            scheduler.submit(() -> {
                workersStarted.countDown();
                try {
                    resumeWorkers.await();
                } catch (InterruptedException interrupt) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(workersStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        int count = IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 84;
        int permanentlyDeleted = 0;
        try {
            for (int i = 0; i < count; i++) {
                Files.writeString(incoming.resolve("test-book-" + i + ".txt"), "initial-" + i);
            }
            assertThat(adapter.scanNow("c1").status()).isEqualTo("SCAN_SCHEDULED:" + count);
            for (int i = 0; i < count; i += 7) {
                Path file = incoming.resolve("test-book-" + i + ".txt");
                Files.delete(file);
                if (i % 14 == 0) {
                    Files.writeString(file, "recreated-" + i);
                } else {
                    permanentlyDeleted++;
                }
            }
            assertThat(adapter.scanNow("c1").status())
                    .isEqualTo("SCAN_SCHEDULED:" + (count - permanentlyDeleted));
            assertThat(pending.size())
                    .isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES);
            assertThat(scheduler.getQueue().size())
                    .isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 1);
        } finally {
            resumeWorkers.countDown();
        }

        int expectedReady = count - permanentlyDeleted;
        await(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file
                 WHERE collection_id='c1' AND status='READY'
                """, Integer.class) == expectedReady, Duration.ofSeconds(60));

        for (int i = 7; i < count; i += 14) {
            String path = incoming.resolve("test-book-" + i + ".txt")
                    .toAbsolutePath().normalize().toString();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM incoming_folder_file
                     WHERE collection_id='c1' AND file_path=? AND status='READY'
                    """, Integer.class, path)).isZero();
        }
        assertThat(pending.size())
                .isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES);
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 120)
    void reconfigureWhileOverflowIsPendingDiscardsOldFolderAndProcessesOnlyNewFiles() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path oldFolder = Files.createDirectory(tempDir.resolve("old-overflow-folder"));
        Path newFolder = Files.createDirectory(tempDir.resolve("new-overflow-folder"));
        adapter.configure("c1", oldFolder, false, 3600, 3600);

        var schedulerField = IncomingFolderWatchAdapter.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        var executor = (java.util.concurrent.ScheduledThreadPoolExecutor) schedulerField.get(adapter);
        var started = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        final int oldCount = IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 32;
        final int newCount = 24;
        try {
            for (int i = 0; i < oldCount; i++) {
                Files.writeString(oldFolder.resolve("old-" + i + ".txt"), "old-test-" + i);
            }
            assertThat(adapter.scanNow("c1").status()).isEqualTo("SCAN_SCHEDULED:" + oldCount);
            for (int i = 0; i < newCount; i++) {
                Files.writeString(newFolder.resolve("new-" + i + ".txt"), "new-test-" + i);
            }
            adapter.configure("c1", newFolder, false, 1, 1);
            assertThat(adapter.scanNow("c1").status()).isEqualTo("SCAN_SCHEDULED:" + newCount);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM incoming_folder_file
                     WHERE collection_id='c1' AND file_path LIKE ?
                    """, Integer.class, oldFolder.toAbsolutePath().normalize() + "%")).isZero();
        } finally {
            release.countDown();
        }

        await(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file
                 WHERE collection_id='c1' AND status='READY'
                """, Integer.class) == newCount, Duration.ofSeconds(40));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file WHERE collection_id='c1'
                """, Integer.class)).isEqualTo(newCount);

        var pendingField = IncomingFolderWatchAdapter.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        var pending = (java.util.Map<?, ?>) pendingField.get(adapter);
        assertThat(pending.size()).isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES);
        assertThat(executor.getQueue().size())
                .isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 1);
    }

    @Test
    void bulkManualScanSchedulesAllSupportedFilesWithoutRetainingDirectorySnapshot() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path incoming = Files.createDirectory(tempDir.resolve("bulk-import"));
        adapter.configure("c1", incoming, false, 1, 1);
        for (int i = 0; i < 64; i++) {
            Files.writeString(incoming.resolve("book-" + i + ".txt"), "content-" + i);
        }
        Files.writeString(incoming.resolve("not-a-book.unknown"), "not a supported format");
        assertThat(adapter.scanNow("c1").status()).isEqualTo("SCAN_SCHEDULED:64");
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM incoming_folder_file", Integer.class) == 64,
                Duration.ofSeconds(8));
    }

    @Test
    void oversizedManualScanUsesBoundedTimersAndReplaysOverflowFromSqlite() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path incoming = Files.createDirectory(tempDir.resolve("bounded-incoming"));
        // Disabled watches may still be scanned manually; the overflow must not be lost.
        adapter.configure("c1", incoming, false, 1, 1);
        int count = IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 96;
        for (int i = 0; i < count; i++) {
            Files.writeString(incoming.resolve("book-" + i + ".txt"), "unique-book-" + i);
        }

        assertThat(adapter.scanNow("c1").status()).isEqualTo("SCAN_SCHEDULED:" + count);
        var pendingField = IncomingFolderWatchAdapter.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        var schedulerField = IncomingFolderWatchAdapter.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        var pending = (java.util.Map<?, ?>) pendingField.get(adapter);
        var scheduler = (java.util.concurrent.ScheduledThreadPoolExecutor) schedulerField.get(adapter);
        assertThat(pending.size()).isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES);
        // 256 per-file timers plus one shared retry watchdog, regardless of overflow size.
        assertThat(scheduler.getQueue().size())
                .isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 1);

        await(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file
                 WHERE collection_id='c1' AND status='READY'
                """, Integer.class) == count, Duration.ofSeconds(40));
        assertThat(pending.size()).isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES);
    }

    @Test
    void stoppedDisabledWatchDoesNotResumePersistedOverflow() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path incoming = Files.createDirectory(tempDir.resolve("stop-overflow"));
        adapter.configure("c1", incoming, false, 3600, 3600);
        int count = IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 32;
        for (int i = 0; i < count; i++) {
            Files.writeString(incoming.resolve("book-" + i + ".txt"), "data-" + i);
        }
        adapter.scanNow("c1");
        var retryField = IncomingFolderWatchAdapter.class.getDeclaredField("overflowReplayWakeup");
        retryField.setAccessible(true);
        var wakeup = (java.util.concurrent.ScheduledFuture<?>) retryField.get(adapter);
        org.junit.jupiter.api.Assertions.assertNotNull(wakeup);
        assertThat(wakeup.isCancelled()).isFalse();
        adapter.stopMonitoring("c1");
        assertThat(wakeup.isCancelled()).isTrue();
        assertThat(retryField.get(adapter)).isNull();

        var pendingField = IncomingFolderWatchAdapter.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        var pending = (java.util.Map<?, ?>) pendingField.get(adapter);
        assertThat(pending).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file
                 WHERE collection_id='c1' AND status='WAITING'
                """, Integer.class)).isGreaterThan(0);
        Thread.sleep(250);
        assertThat(pending).isEmpty();
    }

    @Test
    void overflowRetryIgnoresWaitingRowsOfStoppedWatchers() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        jdbc.update("INSERT INTO collections(id,name) VALUES('c2','Other collection')");
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path stoppedFolder = Files.createDirectory(tempDir.resolve("stopped-retry-watch"));
        Path activeFolder = Files.createDirectory(tempDir.resolve("active-retry-watch"));
        adapter.configure("c1", stoppedFolder, false, 3600, 3600);
        adapter.configure("c2", activeFolder, false, 3600, 3600);
        adapter.scanNow("c1");
        adapter.stopMonitoring("c1");
        adapter.scanNow("c2");

        String stoppedPath = stoppedFolder.resolve("stopped.txt").toString();
        String activePath = activeFolder.resolve("active.txt").toString();
        for (String[] entry : new String[][]{{"c1", stoppedPath}, {"c2", activePath}}) {
            jdbc.update("""
                    INSERT INTO incoming_folder_file (
                        collection_id, file_path, status, detected_at, updated_at)
                    VALUES (?, ?, 'WAITING', ?, ?)
                    """, entry[0], entry[1], Instant.now().toString(), Instant.now().toString());
        }
        var eligibility = IncomingFolderWatchAdapter.class.getDeclaredMethod(
                "hasEligibleWaitingRowsLocked");
        eligibility.setAccessible(true);
        assertThat((boolean) eligibility.invoke(adapter)).isTrue();

        jdbc.update("""
                UPDATE incoming_folder_file SET status='IMPORTED'
                 WHERE collection_id='c2' AND file_path=?
                """, activePath);
        // Stopped c1 still has WAITING rows, but must not keep the periodic timer running.
        assertThat((boolean) eligibility.invoke(adapter)).isFalse();

        jdbc.update("""
                UPDATE incoming_folder_file SET status='WAITING'
                 WHERE collection_id='c2' AND file_path=?
                """, activePath);
        assertThat((boolean) eligibility.invoke(adapter)).isTrue();
        adapter.stopMonitoring("c2");
        assertThat((boolean) eligibility.invoke(adapter)).isFalse();
    }

    @Test
    void repeatedOverflowUsesOnlyOneSharedRetryWakeup() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path incoming = Files.createDirectory(tempDir.resolve("single-watchdog"));
        adapter.configure("c1", incoming, false, 3600, 3600);
        int count = IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 16;
        for (int i = 0; i < count; i++) {
            Files.writeString(incoming.resolve("book-" + i + ".txt"), "test-" + i);
        }
        adapter.scanNow("c1");

        var retryField = IncomingFolderWatchAdapter.class.getDeclaredField("overflowReplayWakeup");
        retryField.setAccessible(true);
        Object first = retryField.get(adapter);
        assertThat(first).isNotNull();
        adapter.scanNow("c1");
        assertThat(retryField.get(adapter)).isSameAs(first);

        var schedulerField = IncomingFolderWatchAdapter.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        var executor = (java.util.concurrent.ScheduledThreadPoolExecutor) schedulerField.get(adapter);
        assertThat(executor.getQueue().size())
                .isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 1);
    }

    @Test
    void malformedPersistedPathIsQuarantinedWithoutStarvingLaterValidBooks() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path incoming = Files.createDirectory(tempDir.resolve("replay-corrupt-path"));
        adapter.configure("c1", incoming, false, 1, 1);
        // Manual scan enrolls even a disabled watch in durable replay.
        adapter.scanNow("c1");

        String invalid = "!broken" + (char) 0 + "path.txt";
        Path valid = Files.writeString(incoming.resolve("valid-book.txt"), "safe test content");
        String signature = Instant.now().minus(Duration.ofSeconds(30)).toString();
        jdbc.update("""
                INSERT INTO incoming_folder_file(
                    collection_id, file_path, observed_size, observed_mtime,
                    signature_seen_at, fingerprint, status, detected_at, updated_at)
                VALUES(?,?,?,?,?,NULL,'WAITING',?,?)
                """, "c1", invalid, 0L, 0L, signature, signature, signature);
        jdbc.update("""
                INSERT INTO incoming_folder_file(
                    collection_id, file_path, observed_size, observed_mtime,
                    signature_seen_at, fingerprint, status, detected_at, updated_at)
                VALUES(?,?,?,?,?,NULL,'WAITING',?,?)
                """, "c1", valid.toAbsolutePath().normalize().toString(), Files.size(valid),
                Files.getLastModifiedTime(valid).toMillis(), signature, signature, signature);

        var refill = IncomingFolderWatchAdapter.class.getDeclaredMethod("refillPendingFromDatabase");
        refill.setAccessible(true);
        refill.invoke(adapter);

        assertThat(jdbc.queryForObject("""
                SELECT status FROM incoming_folder_file WHERE collection_id='c1' AND file_path=?
                """, String.class, invalid)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT last_error FROM incoming_folder_file WHERE collection_id='c1' AND file_path=?
                """, String.class, invalid)).isEqualTo("INVALID_PATH");
        await(() -> jdbc.queryForObject("""
                SELECT status FROM incoming_folder_file WHERE collection_id='c1' AND file_path=?
                """, String.class, valid.toAbsolutePath().normalize().toString()).equals("READY"),
                Duration.ofSeconds(8));
    }

    @Test
    void overflowReplayHandlesDeletedPathsWithoutSchedulingMissingBooks() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path incoming = Files.createDirectory(tempDir.resolve("overflow-delete"));
        adapter.configure("c1", incoming, false, 1, 1);
        int count = IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 24;
        for (int i = 0; i < count; i++) {
            Files.writeString(incoming.resolve("book-" + i + ".txt"), "data-" + i);
        }
        adapter.scanNow("c1");

        Path deleted = incoming.resolve("book-" + (count - 1) + ".txt");
        Files.delete(deleted);
        // A deleted candidate may have been queued but not yet persisted when the
        // file disappears. Both an absent row and a MISSING row are safe; READY is not.
        await(() -> {
            var states = jdbc.query("""
                    SELECT status FROM incoming_folder_file
                     WHERE collection_id='c1' AND file_path=?
                    """, (rs, row) -> rs.getString(1), deleted.toAbsolutePath().normalize().toString());
            return states.isEmpty() || states.getFirst().equals("MISSING");
        }, Duration.ofSeconds(40));
        assertThat(Files.exists(deleted)).isFalse();
    }

    @Test
    void shutdownClearsManualScanTimersAndFinishesSqliteWorkersOnWindows() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path incoming = Files.createDirectory(tempDir.resolve("shutdown-bounded"));
        adapter.configure("c1", incoming, false, 3600, 3600);
        for (int i = 0; i < IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 12; i++) {
            Files.writeString(incoming.resolve("slow-" + i + ".txt"), "content-" + i);
        }
        adapter.scanNow("c1");
        var pendingField = IncomingFolderWatchAdapter.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        var pending = (java.util.Map<?, ?>) pendingField.get(adapter);
        var schedulerField = IncomingFolderWatchAdapter.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        var scheduler = (java.util.concurrent.ScheduledThreadPoolExecutor) schedulerField.get(adapter);

        adapter.shutdown();

        assertThat(pending).isEmpty();
        assertThat(scheduler.isTerminated()).isTrue();
        assertThat(scheduler.getQueue()).isEmpty();
    }

    @Test
    void cancelledDebounceTimersAreRemovedFromSchedulerQueue() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        var field = IncomingFolderWatchAdapter.class.getDeclaredField("scheduler");
        field.setAccessible(true);
        var scheduler = (java.util.concurrent.ScheduledThreadPoolExecutor) field.get(adapter);
        assertThat(scheduler.getRemoveOnCancelPolicy()).isTrue();
    }

    @Test
    void watchServiceEventUsesRegisteredSettingsWithoutQueryingSqlitePerEvent() throws Exception {
        JdbcTemplate jdbc = spy(metadataJdbc());
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path incoming = Files.createDirectory(tempDir.resolve("event-cache"));
        adapter.configure("c1", incoming, true, 2, 2);
        clearInvocations(jdbc);

        Files.writeString(incoming.resolve("watched.txt"), "event");
        var pendingField = IncomingFolderWatchAdapter.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        await(() -> !((java.util.Map<?, ?>) pendingField.get(adapter)).isEmpty(), Duration.ofSeconds(5));

        long repeatedWatchSettingsQueries = mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("query"))
                .filter(invocation -> invocation.getArguments().length > 0)
                .map(invocation -> invocation.getArguments()[0])
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(sql -> sql.contains("SELECT folder_path, enabled, debounce_seconds, stability_seconds"))
                .count();
        assertThat(repeatedWatchSettingsQueries).isZero();
    }

    @Test
    void deletingFileCancelsQueuedDebounceAndRecreationSchedulesNewOne() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path incoming = Files.createDirectory(tempDir.resolve("delete-debounce"));
        adapter.configure("c1", incoming, true, 30, 30);

        var pendingField = IncomingFolderWatchAdapter.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        var pending = (java.util.Map<?, ?>) pendingField.get(adapter);
        var schedulerField = IncomingFolderWatchAdapter.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        var scheduler = (java.util.concurrent.ScheduledThreadPoolExecutor) schedulerField.get(adapter);

        Path file = incoming.resolve("appearing.txt");
        String key = "c1\u0000" + file.toAbsolutePath().normalize();
        Files.writeString(file, "temporary");
        await(() -> pending.containsKey(key), Duration.ofSeconds(5));
        await(() -> !scheduler.getQueue().isEmpty(), Duration.ofSeconds(5));

        Files.delete(file);
        await(() -> !pending.containsKey(key), Duration.ofSeconds(5));
        await(() -> scheduler.getQueue().isEmpty(), Duration.ofSeconds(5));

        Files.writeString(file, "new-content");
        await(() -> pending.containsKey(key), Duration.ofSeconds(5));
        await(() -> !scheduler.getQueue().isEmpty(), Duration.ofSeconds(5));
    }

    @Test
    void reconfigureRefreshesWatcherRegistrationSettings() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path first = Files.createDirectory(tempDir.resolve("watched-first"));
        Path second = Files.createDirectory(tempDir.resolve("watched-second"));
        adapter.configure("c1", first, true, 1, 1);

        var registrationsField = IncomingFolderWatchAdapter.class.getDeclaredField("watchers");
        registrationsField.setAccessible(true);
        var watchers = (java.util.Map<?, ?>) registrationsField.get(adapter);
        Object firstRegistration = watchers.get("c1");
        assertThat(firstRegistration).isNotNull();

        adapter.configure("c1", second, true, 5, 6);
        Object secondRegistration = watchers.get("c1");
        assertThat(secondRegistration).isNotNull().isNotSameAs(firstRegistration);
        var settingsField = secondRegistration.getClass().getDeclaredField("settings");
        settingsField.setAccessible(true);
        Object settings = settingsField.get(secondRegistration);
        var debounce = settings.getClass().getDeclaredMethod("debounceSeconds");
        var stability = settings.getClass().getDeclaredMethod("stabilitySeconds");
        var folder = settings.getClass().getDeclaredMethod("folder");
        folder.setAccessible(true);
        debounce.setAccessible(true);
        stability.setAccessible(true);
        assertThat(folder.invoke(settings)).isEqualTo(second);
        assertThat(debounce.invoke(settings)).isEqualTo(5);
        assertThat(stability.invoke(settings)).isEqualTo(6);
    }

    @Test
    void stabilityGateThenContentFingerprintPreventsSecondImportCandidate() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        DomainEventPublisher events = mock(DomainEventPublisher.class);
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), events);
        Path incoming = Files.createDirectory(tempDir.resolve("incoming"));
        adapter.configure("c1", incoming, false, 1, 1);

        Path first = incoming.resolve("one.txt");
        Files.writeString(first, "same-content");
        adapter.scanNow("c1");
        assertThat(adapter.listReady("c1", 10)).isEmpty();

        await(() -> !adapter.listReady("c1", 10).isEmpty(), Duration.ofSeconds(5));
        var candidate = adapter.listReady("c1", 10).getFirst();
        assertThat(adapter.claimReady("c1", candidate.file(), candidate.fingerprint())).isTrue();
        adapter.markImported("c1", candidate.file(), candidate.fingerprint(), 1, 0, 0);

        Path duplicate = incoming.resolve("copy.txt");
        Files.writeString(duplicate, "same-content");
        adapter.scanNow("c1");
        await(() -> adapter.findState("c1").orElseThrow().duplicateContentCount() == 1, Duration.ofSeconds(5));

        assertThat(adapter.listReady("c1", 10)).isEmpty();
        verify(events, times(1)).publish(any(IncomingFolderFileReadyEvent.class));
    }

    @Test
    void importedFingerprintExistenceIsScopedToCollectionAndAnotherFile() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path path = tempDir.resolve("imported-one.txt").toAbsolutePath().normalize();
        String sameFingerprint = "synthetic-fingerprint-a";
        String timestamp = Instant.now().toString();
        jdbc.update("""
                INSERT INTO incoming_folder_watch(collection_id, folder_path, enabled, updated_at)
                VALUES('c1', ?, 0, ?)
                """, tempDir.toString(), timestamp);
        jdbc.update("""
                INSERT INTO incoming_folder_file(collection_id,file_path,fingerprint,status,detected_at,updated_at)
                VALUES('c1', ?, ?, 'IMPORTED', ?, ?)
                """, path.toString(), sameFingerprint, timestamp, timestamp);

        // Query the real adapter method rather than copying its SQL into a test.
        var probe = IncomingFolderWatchAdapter.class.getDeclaredMethod(
                "alreadyImportedFingerprint", String.class, Path.class, String.class);
        probe.setAccessible(true);
        assertThat((Boolean) probe.invoke(adapter, "c1", path, sameFingerprint))
                .as("an imported file cannot make itself a duplicate").isFalse();
        assertThat((Boolean) probe.invoke(adapter, "c1", tempDir.resolve("different.txt"), sameFingerprint))
                .as("a second path with identical imported bytes is a duplicate").isTrue();
        assertThat((Boolean) probe.invoke(adapter, "c1", tempDir.resolve("different.txt"), "other-fingerprint"))
                .as("nonmatching hashes do not indicate a duplicate").isFalse();
        assertThat((Boolean) probe.invoke(adapter, "c2", tempDir.resolve("different.txt"), sameFingerprint))
                .as("an imported book from another collection is not a duplicate").isFalse();
    }

    @Test
    void changingFileDuringStabilityWindowDelaysReadyPublication() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        DomainEventPublisher events = mock(DomainEventPublisher.class);
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), events);
        Path incoming = Files.createDirectory(tempDir.resolve("incoming"));
        adapter.configure("c1", incoming, false, 1, 1);
        Path file = incoming.resolve("copying.txt");
        Files.writeString(file, "partial");
        adapter.scanNow("c1");

        Thread.sleep(600);
        Files.writeString(file, "-more", java.nio.file.StandardOpenOption.APPEND);
        Thread.sleep(700);
        assertThat(adapter.listReady("c1", 10)).isEmpty();
        verify(events, never()).publish(any(IncomingFolderFileReadyEvent.class));

        await(() -> !adapter.listReady("c1", 10).isEmpty(), Duration.ofSeconds(4));
        // READY is persisted immediately before the asynchronous notification is published.
        // Wait for the notification instead of racing the scheduler thread after observing READY.
        verify(events, timeout(1000).times(1)).publish(any(IncomingFolderFileReadyEvent.class));
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 180)
    void hardKilledSqliteWriterRollsBackOpenTransactionAndReplaysCommittedClaims() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("PRAGMA synchronous=FULL");
        Path database = tempDir.resolve("meta.db");
        Path incoming = Files.createDirectory(tempDir.resolve("crash-incoming-books"));
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        adapter.configure("c1", incoming, false, 3600, 3600);

        // Force some candidates into durable SQLite overflow beyond 256 scheduled futures.
        final int total = IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 32;
        for (int i = 0; i < total; i++) {
            Files.writeString(incoming.resolve("crash-book-" + i + ".txt"), "disposable " + i);
        }
        assertThat(adapter.scanNow("c1").status()).isEqualTo("SCAN_SCHEDULED:" + total);
        // scanNow schedules observation asynchronously; the first 256 timers may not
        // have persisted yet when scanNow returns. Wait for the actual SQLite state.
        await(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file WHERE collection_id='c1' AND status='WAITING'
                """, Integer.class) == total, Duration.ofSeconds(30));
        adapter.shutdown();
        adapter = null;

        Path openTxnSignal = tempDir.resolve("uncommitted-open.signal");
        Path openTxnLog = tempDir.resolve("uncommitted-worker.log");
        Process uncommitted = startCrashWorker(database, "UNCOMMITTED", openTxnSignal, openTxnLog, total);
        try {
            await(() -> Files.exists(openTxnSignal) || !uncommitted.isAlive(), Duration.ofSeconds(30));
            assertThat(Files.exists(openTxnSignal)).as(Files.readString(openTxnLog)).isTrue();
            assertThat(uncommitted.isAlive()).as("worker must still hold a SQLite write transaction").isTrue();
        } finally {
            if (uncommitted.isAlive()) uncommitted.destroyForcibly();
            assertThat(uncommitted.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }

        JdbcTemplate fresh = reopenedMetadataJdbc(database);
        // Windows may briefly retain native SQLite WAL handles after killing the child.
        // Retry only the exact transient VFS truncate failure; all other SQL errors fail.
        await(() -> durableWatcherState(fresh, "WAITING", total), Duration.ofSeconds(20));

        // Starting a fresh adapter must replay the durable overflow, not drop late books.
        fresh.update("""
                UPDATE incoming_folder_watch SET enabled=1, debounce_seconds=1, stability_seconds=1
                 WHERE collection_id='c1'
                """);
        adapter = new IncomingFolderWatchAdapter(fresh, mock(Flyway.class), mock(DomainEventPublisher.class));
        adapter.startConfigured();
        await(() -> fresh.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file WHERE collection_id='c1' AND status='READY'
                """, Integer.class) == total, Duration.ofSeconds(75));
        assertThat(fresh.queryForObject("SELECT COUNT(*) FROM incoming_folder_file", Integer.class)).isEqualTo(total);
        var pendingField = IncomingFolderWatchAdapter.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        var schedulerField = IncomingFolderWatchAdapter.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        var pending = (java.util.Map<?, ?>) pendingField.get(adapter);
        var scheduler = (java.util.concurrent.ScheduledThreadPoolExecutor) schedulerField.get(adapter);
        assertThat(pending.size()).isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES);
        assertThat(scheduler.getQueue().size())
                .isLessThanOrEqualTo(IncomingFolderWatchAdapter.MAX_PENDING_CANDIDATES + 1);
        adapter.shutdown();
        adapter = null;

        // A second abrupt JVM exit, this time just AFTER COMMIT, must not strand PROCESSING.
        Path committedSignal = tempDir.resolve("committed.signal");
        Path committedLog = tempDir.resolve("committed-worker.log");
        Process committed = startCrashWorker(database, "COMMITTED", committedSignal, committedLog, total);
        try {
            assertThat(committed.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
                    .as(Files.exists(committedLog) ? Files.readString(committedLog) : "child log missing").isTrue();
            assertThat(committed.exitValue()).isEqualTo(83);
            assertThat(Files.exists(committedSignal)).isTrue();
        } finally {
            if (committed.isAlive()) {
                committed.destroyForcibly();
                committed.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        JdbcTemplate afterCommit = reopenedMetadataJdbc(database);
        await(() -> durableWatcherState(afterCommit, "PROCESSING", total), Duration.ofSeconds(20));
        // Disable automatic rescans to specifically prove the startup PROCESSING -> READY repair.
        afterCommit.update("UPDATE incoming_folder_watch SET enabled=0 WHERE collection_id='c1'");
        adapter = new IncomingFolderWatchAdapter(afterCommit, mock(Flyway.class), mock(DomainEventPublisher.class));
        adapter.startConfigured();
        assertThat(afterCommit.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file WHERE collection_id='c1' AND status='READY'
                """, Integer.class)).isEqualTo(total);
        assertThat(afterCommit.queryForObject("SELECT COUNT(*) FROM incoming_folder_file", Integer.class)).isEqualTo(total);
    }

    private static boolean durableWatcherState(JdbcTemplate jdbc, String status, int expected) {
        try {
            return "ok".equals(jdbc.queryForObject("PRAGMA integrity_check", String.class))
                    && expected == jdbc.queryForObject("""
                    SELECT COUNT(*) FROM incoming_folder_file
                     WHERE collection_id='c1' AND status=?
                    """, Integer.class, status)
                    && expected == jdbc.queryForObject("""
                    SELECT COUNT(*) FROM incoming_folder_file WHERE collection_id='c1'
                    """, Integer.class);
        } catch (org.springframework.dao.DataAccessException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof org.sqlite.SQLiteException sqlite
                    && sqlite.getMessage() != null
                    && sqlite.getMessage().contains("SQLITE_IOERR_TRUNCATE")) {
                return false;
            }
            throw failure;
        }
    }

    private JdbcTemplate reopenedMetadataJdbc(Path database) {
        SQLiteDataSource datasource = new SQLiteDataSource();
        datasource.setUrl("jdbc:sqlite:" + database);
        return new JdbcTemplate(datasource);
    }

    private Process startCrashWorker(Path database, String mode, Path signal, Path log, int expected)
            throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-cp", classpath,
                IncomingFolderWatchCrashWorker.class.getName(),
                database.toString(), mode, signal.toString(), Integer.toString(expected))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    @Test
    void processingClaimReturnsToReadyAfterRestart() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path incoming = Files.createDirectory(tempDir.resolve("incoming"));
        adapter.configure("c1", incoming, false, 1, 1);
        Path file = incoming.resolve("book.txt");
        Files.writeString(file, "book");
        adapter.scanNow("c1");
        await(() -> !adapter.listReady("c1", 10).isEmpty(), Duration.ofSeconds(5));
        var candidate = adapter.listReady("c1", 10).getFirst();
        assertThat(adapter.claimReady("c1", candidate.file(), candidate.fingerprint())).isTrue();
        assertThat(adapter.listReady("c1", 10)).isEmpty();
        adapter.shutdown();

        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        adapter.startConfigured();
        assertThat(adapter.listReady("c1", 10)).hasSize(1);
    }


    @Test
    void releaseClaimReturnsProcessingCandidateToReadyWithoutChangingFingerprint() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path incoming = Files.createDirectory(tempDir.resolve("incoming-release"));
        adapter.configure("c1", incoming, false, 1, 1);
        Path file = incoming.resolve("retry.txt");
        Files.writeString(file, "retry");
        adapter.scanNow("c1");
        await(() -> !adapter.listReady("c1", 10).isEmpty(), Duration.ofSeconds(5));
        var candidate = adapter.listReady("c1", 10).getFirst();

        assertThat(adapter.claimReady("c1", candidate.file(), candidate.fingerprint())).isTrue();
        adapter.releaseClaim("c1", candidate.file(), candidate.fingerprint());

        assertThat(adapter.listReady("c1", 10)).singleElement()
                .satisfies(requeued -> assertThat(requeued.fingerprint()).isEqualTo(candidate.fingerprint()));
    }

    @Test
    void scanIgnoresSymlinkedIncomingFile() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        DomainEventPublisher events = mock(DomainEventPublisher.class);
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), events);
        Path incoming = Files.createDirectory(tempDir.resolve("incoming-symlink-file"));
        adapter.configure("c1", incoming, false, 1, 1);
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "outside");
        Path link = incoming.resolve("linked.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            assumeTrue(false, "Symbolic links are unavailable on this filesystem");
        }

        adapter.scanNow("c1");
        Thread.sleep(1200);

        assertThat(adapter.listReady("c1", 10)).isEmpty();
        verify(events, never()).publish(any(IncomingFolderFileReadyEvent.class));
    }

    @Test
    void incomingDirectoryBehindSymlinkedParentIsRejected() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path realParent = Files.createDirectory(tempDir.resolve("real-parent"));
        Path realIncoming = Files.createDirectory(realParent.resolve("incoming"));
        Path linkedParent = tempDir.resolve("linked-parent");
        try {
            Files.createSymbolicLink(linkedParent, realParent);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            assumeTrue(false, "Symbolic links are unavailable on this filesystem");
        }

        var state = adapter.configure("c1", linkedParent.resolve(realIncoming.getFileName()), false, 1, 1);

        assertThat(state.status()).isEqualTo("FOLDER_MISSING");
    }

    @Test
    void symlinkedIncomingDirectoryIsNotTreatedAsConfiguredFolder() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        adapter = new IncomingFolderWatchAdapter(jdbc, mock(Flyway.class), mock(DomainEventPublisher.class));
        Path real = Files.createDirectory(tempDir.resolve("real-incoming"));
        Path link = tempDir.resolve("incoming-link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            assumeTrue(false, "Symbolic links are unavailable on this filesystem");
        }

        var state = adapter.configure("c1", link, false, 1, 1);

        assertThat(state.status()).isEqualTo("FOLDER_MISSING");
    }

    private JdbcTemplate metadataJdbc() {
        // Production MetadataDatabaseConfig applies the same connection-local
        // foreign-key and 15-second SQLITE_BUSY wait to every pooled connection.
        // A zero-timeout test DataSource makes the real WatchService + SQLite
        // concurrent replay regression fail at the first short writer lock.
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(15_000);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("meta.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("PRAGMA busy_timeout", Integer.class)).isEqualTo(15_000);
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
        jdbc.execute("CREATE TABLE collections(id TEXT PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.update("INSERT INTO collections(id,name) VALUES('c1','Test')");
        jdbc.execute("""
                CREATE TABLE incoming_folder_watch(
                    collection_id TEXT PRIMARY KEY, folder_path TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 0,
                    debounce_seconds INTEGER NOT NULL DEFAULT 2, stability_seconds INTEGER NOT NULL DEFAULT 3,
                    last_scan_at TEXT, last_status TEXT NOT NULL DEFAULT 'CONFIGURED', updated_at TEXT NOT NULL,
                    FOREIGN KEY(collection_id) REFERENCES collections(id) ON DELETE CASCADE)
                """);
        jdbc.execute("""
                CREATE TABLE incoming_folder_file(
                    collection_id TEXT NOT NULL, file_path TEXT NOT NULL, observed_size INTEGER NOT NULL DEFAULT 0,
                    observed_mtime INTEGER NOT NULL DEFAULT 0, signature_seen_at TEXT, fingerprint TEXT,
                    status TEXT NOT NULL DEFAULT 'WAITING', detected_at TEXT NOT NULL, imported_at TEXT,
                    last_error TEXT, imported_count INTEGER NOT NULL DEFAULT 0, duplicate_count INTEGER NOT NULL DEFAULT 0,
                    error_count INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL,
                    PRIMARY KEY(collection_id,file_path),
                    FOREIGN KEY(collection_id) REFERENCES incoming_folder_watch(collection_id) ON DELETE CASCADE)
                """);
        return jdbc;
    }

    private static void await(Check check, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (check.get()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("condition not reached before " + timeout);
    }

    @FunctionalInterface private interface Check { boolean get() throws Exception; }
}
