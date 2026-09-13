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

class IncomingFolderWatchAdapterTest {
    @TempDir Path tempDir;
    private IncomingFolderWatchAdapter adapter;

    @AfterEach
    void close() {
        if (adapter != null) adapter.shutdown();
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
        verify(events, times(1)).publish(any(IncomingFolderFileReadyEvent.class));
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
