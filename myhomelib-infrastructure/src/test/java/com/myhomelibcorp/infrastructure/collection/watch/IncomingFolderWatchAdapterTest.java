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
