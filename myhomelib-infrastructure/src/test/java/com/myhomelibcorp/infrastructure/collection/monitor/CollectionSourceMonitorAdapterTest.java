package com.myhomelibcorp.infrastructure.collection.monitor;

import com.myhomelibcorp.application.event.CollectionSourceUpdateAvailableEvent;
import com.myhomelibcorp.shared.event.DomainEventPublisher;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CollectionSourceMonitorAdapterTest {
    @TempDir Path tempDir;
    private CollectionSourceMonitorAdapter adapter;

    @AfterEach
    void tearDown() {
        if (adapter != null) adapter.shutdown();
    }

    @Test
    void baselineChangeManualCheckAndAcknowledgeAreStable() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        adapter = new CollectionSourceMonitorAdapter(jdbc, mock(Flyway.class), publisher);
        Path source = tempDir.resolve("catalog.inpx");
        writeZip(source, "a.inp", "one");

        var configured = adapter.configure("c1", source, false, 60);
        assertThat(configured.updateAvailable()).isFalse();
        assertThat(configured.baselineFingerprint()).isNotBlank();

        writeZip(source, "a.inp", "two");
        var changed = adapter.checkNow("c1");
        assertThat(changed.updateAvailable()).isTrue();
        verify(publisher, times(1)).publish(any(CollectionSourceUpdateAvailableEvent.class));

        var repeated = adapter.checkNow("c1");
        assertThat(repeated.updateAvailable()).isTrue();
        verifyNoMoreInteractions(publisher);

        var applied = adapter.markApplied("c1", source);
        assertThat(applied.updateAvailable()).isFalse();
        assertThat(applied.baselineFingerprint()).isEqualTo(applied.observedFingerprint());
        assertThat(applied.status()).isEqualTo("APPLIED");
    }

    @Test
    void watchServiceDebouncesMatchingSourceAndIgnoresUnrelatedFiles() throws Exception {
        JdbcTemplate jdbc = metadataJdbc();
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        adapter = new CollectionSourceMonitorAdapter(jdbc, mock(Flyway.class), publisher);
        Path source = tempDir.resolve("catalog.inpx");
        writeZip(source, "a.inp", "one");
        adapter.configure("c1", source, true, 1);

        Files.writeString(tempDir.resolve("unrelated.txt"), "noise");
        Thread.sleep(1300);
        assertThat(adapter.findState("c1").orElseThrow().updateAvailable()).isFalse();

        writeZip(source, "a.inp", "two");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(6));
        while (Instant.now().isBefore(deadline)
                && !adapter.findState("c1").orElseThrow().updateAvailable()) {
            Thread.sleep(100);
        }
        assertThat(adapter.findState("c1").orElseThrow().updateAvailable()).isTrue();
        // checkNow() persists update_available before publishing the event. Under a loaded
        // test reactor the polling thread can observe the DB update a few milliseconds
        // before the watcher thread invokes the publisher, so wait for that asynchronous
        // side effect instead of racing it.
        verify(publisher, timeout(2_000).times(1))
                .publish(any(CollectionSourceUpdateAvailableEvent.class));
    }

    private JdbcTemplate metadataJdbc() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("meta.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE collections(id TEXT PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.update("INSERT INTO collections(id,name) VALUES('c1','Test')");
        jdbc.execute("""
                CREATE TABLE collection_source_watch(
                    collection_id TEXT PRIMARY KEY, source_file TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 0, debounce_seconds INTEGER NOT NULL DEFAULT 60,
                    baseline_fingerprint TEXT, observed_fingerprint TEXT, last_checked_at TEXT,
                    update_available INTEGER NOT NULL DEFAULT 0, last_status TEXT, updated_at TEXT)
                """);
        return jdbc;
    }

    private static void writeZip(Path path, String entry, String content) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            out.putNextEntry(new ZipEntry(entry));
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
