package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.download.DownloadQueueStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SqliteDownloadQueueAdapterTest {
    @TempDir Path temp;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temp.resolve("meta.db"));
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE online_download_queue (
                    collection_id TEXT NOT NULL,
                    book_id TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    status TEXT NOT NULL,
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    last_attempt TEXT,
                    download_destination TEXT,
                    physical_archive_identity TEXT,
                    resume_information TEXT,
                    last_error TEXT,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY(collection_id, book_id)
                )
                """);
    }

    @Test
    void persistsLifecycleWithoutCredentials() {
        SqliteDownloadQueueAdapter queue = adapter();
        queue.markPending("c1", "b1", "archive.zip", "archive.zip.part");
        queue.markInProgress("c1", "b1");
        queue.markCompleted("c1", "b1", temp.resolve("archive.zip"));

        var row = queue.find("c1", "b1").orElseThrow();
        assertThat(row.status()).isEqualTo(DownloadQueueStatus.COMPLETED);
        assertThat(row.retryCount()).isEqualTo(1);
        assertThat(row.physicalArchiveIdentity()).isEqualTo("archive.zip");
        assertThat(row.downloadDestination()).endsWith("archive.zip");
        assertThat(row.lastError()).isNull();
    }

    @Test
    void startupRecoveryConvertsInterruptedWorkToPending() {
        SqliteDownloadQueueAdapter first = adapter();
        first.markPending("c1", "b1", "book.fb2", "book.fb2.part");
        first.markInProgress("c1", "b1");
        assertThat(first.find("c1", "b1").orElseThrow().status()).isEqualTo(DownloadQueueStatus.IN_PROGRESS);

        SqliteDownloadQueueAdapter afterRestart = adapter();
        var row = afterRestart.find("c1", "b1").orElseThrow();
        assertThat(row.status()).isEqualTo(DownloadQueueStatus.PENDING);
        assertThat(row.resumeInformation()).isEqualTo("book.fb2.part");
    }

    @Test
    void sanitizesPersistedFailureText() {
        SqliteDownloadQueueAdapter queue = adapter();
        queue.markPending("c1", "b1", "book.fb2", null);
        queue.markFailed("c1", "b1", "HTTP 403 token=super-secret", "book.fb2.part");

        var row = queue.find("c1", "b1").orElseThrow();
        assertThat(row.status()).isEqualTo(DownloadQueueStatus.FAILED);
        assertThat(row.lastError()).doesNotContain("super-secret").contains("redacted");
    }

    private SqliteDownloadQueueAdapter adapter() {
        return new SqliteDownloadQueueAdapter(jdbc, mock(Flyway.class));
    }
}
