package com.myhomelibcorp.infrastructure.collection.watch;

import com.myhomelibcorp.application.event.IncomingFolderFileReadyEvent;
import com.myhomelibcorp.shared.event.DomainEventPublisher;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
        assertThat(scheduler.getQueue()).isNotEmpty();

        Files.delete(file);
        await(() -> !pending.containsKey(key), Duration.ofSeconds(5));
        await(() -> scheduler.getQueue().isEmpty(), Duration.ofSeconds(5));

        Files.writeString(file, "new-content");
        await(() -> pending.containsKey(key), Duration.ofSeconds(5));
        assertThat(scheduler.getQueue()).isNotEmpty();
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
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("meta.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("PRAGMA foreign_keys=ON");
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
