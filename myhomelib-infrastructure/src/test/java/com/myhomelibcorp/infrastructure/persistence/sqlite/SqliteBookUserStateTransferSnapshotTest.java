package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.Function;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Copying multiple SQLite tables must see one coherent point-in-time source snapshot. */
class SqliteBookUserStateTransferSnapshotTest {
    @TempDir Path temp;

    @Test
    void concurrentSourceWriteBetweenProgressAndHistoryReadsDoesNotMixSnapshots() throws Exception {
        verifyCoherentSnapshot(1);
    }

    @Test
    void concurrentSourceWriteBetweenSeparateFourHundredBookChunksKeepsSnapshot() throws Exception {
        verifyCoherentSnapshot(401);
    }

    private void verifyCoherentSnapshot(int bookCount) throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(25), () -> {
            String id = "11111111-1111-1111-1111-111111111111";
            Path sourcePath = temp.resolve("source.db");
            Path targetPath = temp.resolve("target.db");
            SQLiteDataSource sourceDs = new SQLiteDataSource();
            sourceDs.setUrl("jdbc:sqlite:" + sourcePath);
            CountDownLatch progressWasCopied = new CountDownLatch(1);
            CountDownLatch resumeTransfer = new CountDownLatch(1);
            SQLiteDataSource targetDs = new SQLiteDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    Connection conn = super.getConnection();
                    Function.create(conn, "pause_after_progress", new Function() {
                        @Override protected void xFunc() throws SQLException {
                            progressWasCopied.countDown();
                            try {
                                if (!resumeTransfer.await(12, TimeUnit.SECONDS)) {
                                    throw new SQLException("Timed out waiting for source writer");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new SQLException("Interrupted while pausing transfer", interrupted);
                            }
                            result(1);
                        }
                    });
                    return conn;
                }
            };
            targetDs.setUrl("jdbc:sqlite:" + targetPath);
            JdbcTemplate source = new JdbcTemplate(sourceDs);
            JdbcTemplate target = new JdbcTemplate(targetDs);
            // WAL permits writes while the source has a pinned read snapshot.
            source.execute("PRAGMA journal_mode=WAL");
            for (JdbcTemplate db : List.of(source, target)) {
                db.execute("CREATE TABLE books(id TEXT PRIMARY KEY,progress INTEGER NOT NULL DEFAULT 0)");
                db.execute("""
                    CREATE TABLE reading_progress(book_id TEXT PRIMARY KEY,anchor_id TEXT,
                      text_anchor_id TEXT,audio_anchor_id TEXT,paragraph_index INTEGER DEFAULT 0,
                      paragraph_id TEXT,char_offset INTEGER DEFAULT 0,percent REAL DEFAULT 0,
                      chapter_title TEXT,chapter_id TEXT,updated_at TEXT,
                      reading_time_seconds INTEGER DEFAULT 0,last_device TEXT,revision INTEGER DEFAULT 0)
                    """);
                db.execute("CREATE TABLE reading_history(book_id TEXT PRIMARY KEY,last_opened_at TEXT,open_count INTEGER)");
                db.execute("CREATE TABLE reading_stats(book_id TEXT PRIMARY KEY,first_read_at TEXT,last_read_at TEXT,total_reading_seconds INTEGER,reading_sessions INTEGER,start_percent INTEGER,end_percent INTEGER,current_percent INTEGER,completed_at TEXT)");
                db.execute("CREATE TABLE bookmarks(id TEXT PRIMARY KEY,book_id TEXT NOT NULL,paragraph_id TEXT,char_offset INTEGER,position REAL,chapter_title TEXT,context TEXT,created_at TEXT)");
                db.execute("CREATE TABLE reader_book_preferences(book_id TEXT PRIMARY KEY,preferences_json TEXT,updated_at TEXT)");
                db.execute("CREATE TABLE reader_preference_overrides(scope_type TEXT,scope_key TEXT,preferences_json TEXT,updated_at TEXT,PRIMARY KEY(scope_type,scope_key))");
            }
            source.update("INSERT INTO reading_progress(book_id,anchor_id,percent) VALUES(?,?,?)", id, "before", 20);
            source.update("INSERT INTO reading_history(book_id,last_opened_at,open_count) VALUES(?,?,?)", id, "before", 1);
            target.update("INSERT INTO books(id,progress) VALUES(?,0)", id);
            String laterId = "22222222-2222-2222-2222-222222222222";
            if (bookCount > 1) {
                source.update("INSERT INTO reading_progress(book_id,anchor_id,percent) VALUES(?,?,?)", laterId, "before", 30);
                source.update("INSERT INTO reading_history(book_id,last_opened_at,open_count) VALUES(?,?,?)", laterId, "before", 1);
                target.update("INSERT INTO books(id,progress) VALUES(?,0)", laterId);
            }
            target.execute("CREATE TRIGGER progress_copy_pause AFTER INSERT ON reading_progress BEGIN SELECT pause_after_progress(); END");

            Collection src = new Collection("source", "Source", temp, sourcePath.toString(), 0, null, null, null, null);
            Collection dst = new Collection("target", "Target", temp, targetPath.toString(), 0, null, null, null, null);
            CollectionManager manager = mock(CollectionManager.class);
            when(manager.getCurrentCollection()).thenReturn(dst);
            when(manager.getCurrentJdbcTemplate()).thenReturn(target);
            when(manager.getCurrentDataSource()).thenReturn(targetDs);
            var adapter = new SqliteBookUserStateTransferAdapter(manager);
            java.util.List<BookId> requestedIds = new java.util.ArrayList<>();
            requestedIds.add(BookId.fromString(id));
            for (int n = 1; n < bookCount - 1; n++) {
                requestedIds.add(BookId.fromString(String.format("00000000-0000-0000-0000-%012d", n)));
            }
            if (bookCount > 1) requestedIds.add(BookId.fromString(laterId));
            try (var workers = Executors.newSingleThreadExecutor()) {
                var transfer = workers.submit(() -> adapter.transferCopiedBookState(src, dst, requestedIds));
                try {
                    assertThat(progressWasCopied.await(10, TimeUnit.SECONDS)).as("paused after progress was copied").isTrue();
                    // A new committed source version arrives between SELECTs of different tables.
                    source.update("UPDATE reading_progress SET anchor_id='after',percent=90 WHERE book_id=?", id);
                    source.update("UPDATE reading_history SET last_opened_at='after',open_count=2 WHERE book_id=?", id);
                    if (bookCount > 1) {
                        source.update("UPDATE reading_progress SET anchor_id='after',percent=90 WHERE book_id=?", laterId);
                        source.update("UPDATE reading_history SET last_opened_at='after',open_count=2 WHERE book_id=?", laterId);
                    }
                } finally {
                    resumeTransfer.countDown();
                }
                transfer.get(10, TimeUnit.SECONDS);
            }
            assertThat(target.queryForObject("SELECT anchor_id FROM reading_progress WHERE book_id=?", String.class, id)).isEqualTo("before");
            assertThat(target.queryForObject("SELECT last_opened_at FROM reading_history WHERE book_id=?", String.class, id)).isEqualTo("before");
            assertThat(source.queryForObject("SELECT last_opened_at FROM reading_history WHERE book_id=?", String.class, id)).isEqualTo("after");
            if (bookCount > 1) {
                assertThat(target.queryForObject("SELECT anchor_id FROM reading_progress WHERE book_id=?", String.class, laterId)).isEqualTo("before");
                assertThat(target.queryForObject("SELECT last_opened_at FROM reading_history WHERE book_id=?", String.class, laterId)).isEqualTo("before");
                assertThat(source.queryForObject("SELECT last_opened_at FROM reading_history WHERE book_id=?", String.class, laterId)).isEqualTo("after");
            }
        });
    }
}
