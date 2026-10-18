package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Production adapter integration: the target projection and user-state rows form one transaction. */
class SqliteBookUserStateTransferAtomicityTest {
    @TempDir Path temporary;

    @Test
    void transferredReadingPositionUpdatesBookShelfProjection() {
        Fixture f = fixture("projection");
        f.sourceJdbc.update("INSERT INTO reading_progress(book_id,anchor_id,text_anchor_id,audio_anchor_id,percent,updated_at) VALUES('11111111-1111-1111-1111-111111111111','audio:9000','text:25','audio:9000',81.7,'2026-10-17')");

        f.transfer.transferCopiedBookState(f.sourceCollection, f.targetCollection, List.of(BookId.fromString("11111111-1111-1111-1111-111111111111")));

        assertThat(f.targetJdbc.queryForObject("SELECT progress FROM books WHERE id='11111111-1111-1111-1111-111111111111'", Integer.class)).isEqualTo(82);
        assertThat(f.targetJdbc.queryForObject("SELECT audio_anchor_id FROM reading_progress WHERE book_id='11111111-1111-1111-1111-111111111111'", String.class)).isEqualTo("audio:9000");
        assertThat(f.targetJdbc.queryForObject("SELECT text_anchor_id FROM reading_progress WHERE book_id='11111111-1111-1111-1111-111111111111'", String.class)).isEqualTo("text:25");
    }

    @Test
    void lateBookmarkCollisionMustRollbackEarlierTransferredProgress() {
        Fixture f = fixture("rollback");
        f.sourceJdbc.update("INSERT INTO reading_progress(book_id,anchor_id,percent,updated_at) VALUES('11111111-1111-1111-1111-111111111111','text:70',70,'2026-10-17')");
        f.sourceJdbc.update("INSERT INTO bookmarks(id,book_id) VALUES('shared','11111111-1111-1111-1111-111111111111')");
        f.targetJdbc.update("INSERT INTO bookmarks(id,book_id) VALUES('shared','different-book')");

        assertThatThrownBy(() -> f.transfer.transferCopiedBookState(f.sourceCollection, f.targetCollection, List.of(BookId.fromString("11111111-1111-1111-1111-111111111111"))))
                .isInstanceOf(RuntimeException.class);
        assertThat(f.targetJdbc.queryForObject("SELECT COUNT(*) FROM reading_progress WHERE book_id='11111111-1111-1111-1111-111111111111'", Integer.class)).isZero();
        assertThat(f.targetJdbc.queryForObject("SELECT progress FROM books WHERE id='11111111-1111-1111-1111-111111111111'", Integer.class)).isZero();
        assertThat(f.targetJdbc.queryForObject("SELECT book_id FROM bookmarks WHERE id='shared'", String.class)).isEqualTo("different-book");
    }

    @Test
    void missingDestinationBookRejectsOrphanedUserProgress() {
        Fixture f = fixture("missing-target");
        f.sourceJdbc.update("INSERT INTO reading_progress(book_id,anchor_id,percent,updated_at) VALUES('22222222-2222-2222-2222-222222222222','text:20',20,'2026-10-17')");

        // A normal copy must have persisted the target book before user state.
        // Foreign-key enforcement is not guaranteed on legacy databases.
        assertThatThrownBy(() -> f.transfer.transferCopiedBookState(f.sourceCollection, f.targetCollection,
                List.of(BookId.fromString("22222222-2222-2222-2222-222222222222"))))
                .isInstanceOf(RuntimeException.class);
        // BookId is a UUID; the matching source row below is intentionally absent
        // from target catalogue, and cannot be persisted as orphan progress.
        assertThat(f.targetJdbc.queryForObject("SELECT COUNT(*) FROM reading_progress", Integer.class)).isZero();
    }

    @Test
    void errorInSecondChunkRollsBackProgressFromFirstChunk() {
        Fixture f = fixture("multiple-chunks");
        java.util.List<BookId> ids = new java.util.ArrayList<>();
        ids.add(BookId.fromString("11111111-1111-1111-1111-111111111111"));
        for (int i = 1; i <= com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteInClauseSupport.MAX_ITEMS; i++) {
            ids.add(new BookId(java.util.UUID.nameUUIDFromBytes(("copy-chunk-" + i)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
        f.sourceJdbc.update("INSERT INTO reading_progress(book_id,anchor_id,percent,updated_at) VALUES(?,?,?,?)",
                ids.getFirst().asString(), "text:95", 95, "2026-10-17");
        f.sourceJdbc.update("INSERT INTO bookmarks(id,book_id) VALUES(?,?)", "shared", ids.getLast().asString());
        f.targetJdbc.update("INSERT INTO books(id,progress) VALUES(?,0)", ids.getLast().asString());
        f.targetJdbc.update("INSERT INTO bookmarks(id,book_id) VALUES('shared','different-book')");

        assertThatThrownBy(() -> f.transfer.transferCopiedBookState(f.sourceCollection, f.targetCollection, ids))
                .isInstanceOf(RuntimeException.class);
        assertThat(f.targetJdbc.queryForObject("SELECT COUNT(*) FROM reading_progress", Integer.class)).isZero();
        assertThat(f.targetJdbc.queryForObject("SELECT progress FROM books WHERE id=?", Integer.class,
                ids.getFirst().asString())).isZero();
    }

    private Fixture fixture(String name) {
        Path sourcePath = temporary.resolve(name + "-source.db");
        Path targetPath = temporary.resolve(name + "-target.db");
        SQLiteDataSource sourceDs = new SQLiteDataSource();
        sourceDs.setUrl("jdbc:sqlite:" + sourcePath);
        SQLiteDataSource targetDs = new SQLiteDataSource();
        targetDs.setUrl("jdbc:sqlite:" + targetPath);
        JdbcTemplate sourceJdbc = new JdbcTemplate(sourceDs);
        JdbcTemplate targetJdbc = new JdbcTemplate(targetDs);
        createTables(sourceJdbc);
        createTables(targetJdbc);
        targetJdbc.update("INSERT INTO books(id,progress) VALUES('11111111-1111-1111-1111-111111111111',0)");
        Collection sourceCollection = new Collection("source-" + name, "Source", temporary, sourcePath.toString(), 0, null, null, null, null);
        Collection targetCollection = new Collection("target-" + name, "Target", temporary, targetPath.toString(), 0, null, null, null, null);
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentCollection()).thenReturn(targetCollection);
        when(manager.getCurrentJdbcTemplate()).thenReturn(targetJdbc);
        when(manager.getCurrentDataSource()).thenReturn(targetDs);
        return new Fixture(sourceJdbc, targetJdbc, sourceCollection, targetCollection, new SqliteBookUserStateTransferAdapter(manager));
    }

    private void createTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE books (id TEXT PRIMARY KEY, progress INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("""
            CREATE TABLE reading_progress (
              book_id TEXT PRIMARY KEY, anchor_id TEXT, text_anchor_id TEXT, audio_anchor_id TEXT,
              paragraph_index INTEGER NOT NULL DEFAULT 0, paragraph_id TEXT, char_offset INTEGER DEFAULT 0,
              percent REAL DEFAULT 0, chapter_title TEXT, chapter_id TEXT, updated_at TEXT,
              reading_time_seconds INTEGER DEFAULT 0, last_device TEXT,
              revision INTEGER NOT NULL DEFAULT 0)
            """);
        jdbc.execute("CREATE TABLE reading_history(book_id TEXT PRIMARY KEY,last_opened_at TEXT,open_count INTEGER)");
        jdbc.execute("CREATE TABLE reading_stats(book_id TEXT PRIMARY KEY,first_read_at TEXT,last_read_at TEXT,total_reading_seconds INTEGER,reading_sessions INTEGER,start_percent INTEGER,end_percent INTEGER,current_percent INTEGER,completed_at TEXT)");
        jdbc.execute("CREATE TABLE bookmarks(id TEXT PRIMARY KEY,book_id TEXT NOT NULL,paragraph_id TEXT,char_offset INTEGER,position REAL,chapter_title TEXT,context TEXT,created_at TEXT)");
        jdbc.execute("CREATE TABLE reader_book_preferences(book_id TEXT PRIMARY KEY,preferences_json TEXT,updated_at TEXT)");
        jdbc.execute("CREATE TABLE reader_preference_overrides(scope_type TEXT,scope_key TEXT,preferences_json TEXT,updated_at TEXT,PRIMARY KEY(scope_type,scope_key))");
    }

    private record Fixture(JdbcTemplate sourceJdbc, JdbcTemplate targetJdbc, Collection sourceCollection,
                           Collection targetCollection, SqliteBookUserStateTransferAdapter transfer) { }
}
