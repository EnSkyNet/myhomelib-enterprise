package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.application.sync.ReadingProgressSyncProjector;
import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class SqliteContinueReadingRepositoryTest {
    private Connection connection;
    private JdbcTemplate jdbc;
    private SqliteContinueReadingRepository repository;
    private CollectionManager manager;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite::memory:");
        connection = sqlite.getConnection();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT,deleted INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE authors(id TEXT PRIMARY KEY,last_name TEXT,first_name TEXT,middle_name TEXT)");
        jdbc.execute("CREATE TABLE book_authors(book_id TEXT,author_id TEXT)");
        jdbc.execute("""
                CREATE TABLE reading_progress(
                    book_id TEXT PRIMARY KEY, anchor_id TEXT, paragraph_index INTEGER DEFAULT 0,
                    paragraph_id TEXT NOT NULL DEFAULT '', char_offset INTEGER NOT NULL DEFAULT 0,
                    percent REAL NOT NULL DEFAULT 0, chapter_title TEXT NOT NULL DEFAULT '',
                    chapter_id TEXT NOT NULL DEFAULT '', updated_at TEXT NOT NULL,
                    reading_time_seconds INTEGER NOT NULL DEFAULT 0, last_device TEXT NOT NULL DEFAULT 'desktop')
                """);
        jdbc.update("INSERT INTO books VALUES('a','Alpha',0),('b','Beta',0),('c','Complete',0),('d','Deleted',1)");
        jdbc.update("INSERT INTO authors VALUES('au','Doe','Jane','')");
        jdbc.update("INSERT INTO book_authors VALUES('b','au')");
        insertProgress("a", 25, "One", "2026-09-12 10:00:00.000", "desktop");
        insertProgress("b", 70, "Seven", "2026-09-12 12:00:00.000", "tablet");
        insertProgress("c", 100, "End", "2026-09-12 13:00:00.000", "web");
        insertProgress("d", 50, "Hidden", "2026-09-12 14:00:00.000", "phone");
        manager = Mockito.mock(CollectionManager.class);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        repository = new SqliteContinueReadingRepository(manager);
    }

    @AfterEach void close() throws Exception { connection.close(); }

    @Test
    void activeShelfIsRecentShowsDeviceAndExcludesCompletedOrDeletedBooks() {
        var items = repository.findActive(10);
        assertThat(items).extracting("bookId").containsExactly("b", "a");
        assertThat(items.getFirst().percent()).isEqualTo(70.0);
        assertThat(items.getFirst().lastDevice()).isEqualTo("tablet");
        assertThat(items.getFirst().authors()).contains("Doe", "Jane");
        assertThat(items.getFirst().chapterTitle()).isEqualTo("Seven");
    }

    @Test
    void remoteResolvedProgressImmediatelyDrivesTheSameShelfAndCompletionRemovesIt() {
        var progressRepo = new SqliteReadingProgressRepository(manager, new SqliteBusyRetryExecutor());
        var projector = new ReadingProgressSyncProjector(progressRepo);
        projector.apply(progressRecord(82.0, "phone-2", 3));

        var synced = repository.findActive(10);
        assertThat(synced.getFirst().bookId()).isEqualTo("a");
        assertThat(synced.getFirst().percent()).isEqualTo(82.0);
        assertThat(synced.getFirst().lastDevice()).isEqualTo("phone-2");

        projector.apply(progressRecord(100.0, "phone-2", 4));
        assertThat(repository.findActive(10)).extracting("bookId").doesNotContain("a", "c", "d");
    }

    private void insertProgress(String bookId, double percent, String chapter, String updatedAt, String device) {
        jdbc.update("""
                INSERT INTO reading_progress(book_id,anchor_id,paragraph_index,paragraph_id,char_offset,percent,
                    chapter_title,chapter_id,updated_at,reading_time_seconds,last_device)
                VALUES(?, '', 0, '', 0, ?, ?, '', ?, 0, ?)
                """, bookId, percent, chapter, updatedAt, device);
    }

    private static SyncRecord progressRecord(double percent, String device, long version) {
        return SyncRecord.live("progress:a", SyncEntityType.READING_PROGRESS, "a", version - 1, version,
                Instant.parse("2026-09-12T16:10:00Z"), device, Map.of(
                        "anchorId", "1:50:0:0", "paragraphIndex", "0", "paragraphId", "p0", "charOffset", "0",
                        "percent", Double.toString(percent), "chapterTitle", "Remote", "chapterId", "c1",
                        "updatedAt", "2026-09-12T19:10:00", "readingTimeSeconds", "120"));
    }
}
