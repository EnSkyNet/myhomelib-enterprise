package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.config.CollectionTransactionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the two-row logical projection of reading progress.
 * A save must never commit reading_progress while books.progress remains stale.
 */
class SqliteReadingProgressRepositoryAtomicityTest {
    @TempDir Path temporary;

    @Test
    void progressAndBookSummaryRollbackTogetherWhenSecondWriteFails() {
        SQLiteDataSource dataSource = dataSource("atomicity.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("""
                INSERT INTO reading_progress(book_id,anchor_id,paragraph_index,paragraph_id,char_offset,percent,
                                              chapter_title,chapter_id,updated_at,reading_time_seconds,last_device)
                VALUES ('book-1','1:10:1:2',1,'p1',2,10.0,'Old','','2026-10-16T10:00:00',5,'desktop')
                """);
        jdbc.update("INSERT INTO books(id,progress) VALUES('book-1',10)");
        jdbc.execute("""
                CREATE TRIGGER fail_progress_summary
                BEFORE UPDATE OF progress ON books
                WHEN NEW.progress = 77
                BEGIN
                    SELECT RAISE(ABORT, 'forced test failure');
                END
                """);

        SqliteReadingProgressRepository repository = repository(dataSource, jdbc);

        assertThrows(RuntimeException.class, () -> repository.save(progress("book-1", "1:77:7:3", 77.0)));

        assertThat(jdbc.queryForObject(
                "SELECT anchor_id FROM reading_progress WHERE book_id='book-1'", String.class))
                .isEqualTo("1:10:1:2");
        assertThat(jdbc.queryForObject(
                "SELECT progress FROM books WHERE id='book-1'", Integer.class))
                .isEqualTo(10);
    }

    @Test
    void missingBookCannotCommitOrphanProgressWhenForeignKeysAreDisabled() {
        SQLiteDataSource dataSource = dataSource("missing-book.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        // Older versions of the schema can lack a foreign key constraint.
        SqliteReadingProgressRepository repository = repository(dataSource, jdbc);

        assertThrows(IllegalStateException.class,
                () -> repository.save(progress("deleted-book", "1:88:0:0", 88.0)));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading_progress WHERE book_id='deleted-book'", Integer.class))
                .isZero();

        // The failed save must not poison the transaction manager or a later valid save.
        jdbc.update("INSERT INTO books(id,progress) VALUES('deleted-book',0)");
        repository.save(progress("deleted-book", "1:88:0:0", 88.0));
        assertThat(jdbc.queryForObject(
                "SELECT progress FROM books WHERE id='deleted-book'", Integer.class))
                .isEqualTo(88);
    }

    @Test
    void upsertUpdatesExistingRowWithoutReplaceDeleteInsertSemantics() {
        SQLiteDataSource dataSource = dataSource("upsert.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("""
                INSERT INTO reading_progress(book_id,anchor_id,paragraph_index,paragraph_id,char_offset,percent,
                                              chapter_title,chapter_id,updated_at,reading_time_seconds,last_device)
                VALUES ('book-1','1:10:1:2',1,'p1',2,10.0,'Old','','2026-10-16T10:00:00',5,'desktop')
                """);
        long beforeRowId = jdbc.queryForObject(
                "SELECT rowid FROM reading_progress WHERE book_id='book-1'", Long.class);
        jdbc.update("INSERT INTO books(id,progress) VALUES('book-1',10)");

        SqliteReadingProgressRepository repository = repository(dataSource, jdbc);
        repository.save(progress("book-1", "1:42:4:8", 42.0));

        assertThat(jdbc.queryForObject(
                "SELECT rowid FROM reading_progress WHERE book_id='book-1'", Long.class))
                .isEqualTo(beforeRowId);
        assertThat(jdbc.queryForObject(
                "SELECT anchor_id FROM reading_progress WHERE book_id='book-1'", String.class))
                .isEqualTo("1:42:4:8");
        assertThat(jdbc.queryForObject(
                "SELECT progress FROM books WHERE id='book-1'", Integer.class))
                .isEqualTo(42);
    }

    @Test
    void syncTombstoneDeletesDetailedProgressAndClearsShelfProjectionAtomically() {
        SQLiteDataSource dataSource = dataSource("delete-success.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("INSERT INTO books(id,progress) VALUES('book-1',64)");
        SqliteReadingProgressRepository repository = repository(dataSource, jdbc);
        repository.save(progress("book-1", "1:64:0:0", 64.0));

        repository.deleteByBookId("book-1");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading_progress WHERE book_id='book-1'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT progress FROM books WHERE id='book-1'", Integer.class)).isZero();
    }

    @Test
    void failedShelfProjectionResetRollsBackDeletedReadingProgress() {
        SQLiteDataSource dataSource = dataSource("delete-rollback.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("INSERT INTO books(id,progress) VALUES('book-1',64)");
        SqliteReadingProgressRepository repository = repository(dataSource, jdbc);
        repository.save(progress("book-1", "1:64:0:0", 64.0));
        jdbc.execute("""
                CREATE TRIGGER fail_shelf_progress_reset
                BEFORE UPDATE OF progress ON books
                WHEN NEW.progress = 0
                BEGIN
                    SELECT RAISE(ABORT, 'test abort for projection reset');
                END
                """);

        assertThrows(RuntimeException.class, () -> repository.deleteByBookId("book-1"));

        assertThat(jdbc.queryForObject(
                "SELECT anchor_id FROM reading_progress WHERE book_id='book-1'", String.class))
                .isEqualTo("1:64:0:0");
        assertThat(jdbc.queryForObject(
                "SELECT progress FROM books WHERE id='book-1'", Integer.class))
                .isEqualTo(64);
    }

    @Test
    void textAndAudioAnchorsRemainIndependentAcrossAlternatingSaves() {
        SQLiteDataSource dataSource = dataSource("media-split.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("INSERT INTO books(id,progress) VALUES('book-1',0)");
        SqliteReadingProgressRepository repository = repository(dataSource, jdbc);
        repository.save(progress("book-1", "1:25:0:0", 25));
        repository.save(progress("book-1", "audio:60000:1:3", 60));
        assertThat(repository.findByBookId("book-1").orElseThrow().getTextAnchorId()).isEqualTo("1:25:0:0");
        assertThat(repository.findByBookId("book-1").orElseThrow().getAudioAnchorId()).isEqualTo("audio:60000:1:3");
        repository.save(progress("book-1", "1:45:0:0", 45));
        var restored = repository.findByBookId("book-1").orElseThrow();
        assertThat(restored.getTextAnchorId()).isEqualTo("1:45:0:0");
        assertThat(restored.getAudioAnchorId()).isEqualTo("audio:60000:1:3");
        assertThat(restored.getAnchorId()).isEqualTo("1:45:0:0"); // legacy last-active projection
    }

    @Test
    void aFailedSummaryUpdateMustNotCommitEitherMediaAnchor() {
        SQLiteDataSource dataSource = dataSource("media-rollback.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("INSERT INTO books(id,progress) VALUES('book-1',0)");
        SqliteReadingProgressRepository repository = repository(dataSource, jdbc);
        repository.save(progress("book-1", "1:20:0:0", 20));
        jdbc.execute("""
                CREATE TRIGGER fail_media_summary BEFORE UPDATE OF progress ON books
                WHEN NEW.progress=82 BEGIN SELECT RAISE(ABORT, 'simulated failure'); END
                """);
        assertThrows(RuntimeException.class, () -> repository.save(progress("book-1", "audio:8000:0:0", 82)));
        var restored = repository.findByBookId("book-1").orElseThrow();
        assertThat(restored.getTextAnchorId()).isEqualTo("1:20:0:0");
        assertThat(restored.getAudioAnchorId()).isNull();
        assertThat(restored.getAnchorId()).isEqualTo("1:20:0:0");
    }

    @Test
    void staleReaderRevisionMustNotOverwriteNewerProgressOrShelfSummary() {
        SQLiteDataSource dataSource = dataSource("revision-conflict.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("INSERT INTO books(id,progress) VALUES('book-1',0)");
        SqliteReadingProgressRepository first = repository(dataSource, jdbc);
        SqliteReadingProgressRepository second = repository(dataSource, jdbc);
        ReadingProgressDto initial = progress("book-1", "1:10:0:0", 10);
        initial.setRevision(-1L); // Must be an absent row
        first.save(initial);
        ReadingProgressDto a = first.findByBookId("book-1").orElseThrow();
        ReadingProgressDto b = second.findByBookId("book-1").orElseThrow();
        assertThat(a.getRevision()).isZero();
        a.setAnchorId("1:40:0:0");
        a.setPercent(40);
        first.save(a);
        b.setAnchorId("1:95:0:0");
        b.setPercent(95);
        assertThrows(ReadingProgressRevisionConflictException.class, () -> second.save(b));
        ReadingProgressDto after = first.findByBookId("book-1").orElseThrow();
        assertThat(after.getAnchorId()).isEqualTo("1:40:0:0");
        assertThat(after.getRevision()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT progress FROM books WHERE id='book-1'", Integer.class))
                .isEqualTo(40);
    }

    @Test
    void insertIfAbsentRejectsConcurrentInsertAndLegacyWriterAdvancesRevision() {
        SQLiteDataSource dataSource = dataSource("revision-new.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("INSERT INTO books(id,progress) VALUES('book-1',0)");
        SqliteReadingProgressRepository repository = repository(dataSource, jdbc);
        ReadingProgressDto fresh = progress("book-1", "1:10:0:0", 10);
        fresh.setRevision(-1L);
        repository.save(fresh);
        assertThrows(ReadingProgressRevisionConflictException.class, () -> repository.save(fresh));
        var stale = repository.findByBookId("book-1").orElseThrow();
        // Non-Reader sync writers do not use CAS yet, but must invalidate stale Reader versions.
        repository.save(progress("book-1", "1:88:0:0", 88));
        assertThat(repository.findByBookId("book-1").orElseThrow().getRevision()).isEqualTo(1L);
        stale.setAnchorId("1:2:0:0");
        assertThrows(ReadingProgressRevisionConflictException.class, () -> repository.save(stale));
    }

    @Test
    void staleAbsenceBeforeAnotherWritersDeletionCannotRecreateOrOverwriteProgress() {
        SQLiteDataSource dataSource = dataSource("stale-absent.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("INSERT INTO books(id,progress) VALUES('b',0)");
        SqliteReadingProgressRepository first = repository(dataSource, jdbc);
        SqliteReadingProgressRepository second = repository(dataSource, jdbc);
        long staleAbsence = second.revisionWhenAbsent("b");
        assertThat(staleAbsence).isEqualTo(-1L);

        ReadingProgressDto initial = progress("b", "1:22:0:0", 22);
        initial.setRevision(first.revisionWhenAbsent("b"));
        first.save(initial);
        first.deleteByBookId("b");
        assertThat(jdbc.queryForObject(
                "SELECT next_revision FROM reading_progress_deleted_generations WHERE book_id='b'", Long.class))
                .isEqualTo(1L);

        ReadingProgressDto late = progress("b", "1:99:0:0", 99);
        late.setRevision(staleAbsence);
        assertThrows(ReadingProgressRevisionConflictException.class, () -> second.save(late));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reading_progress WHERE book_id='b'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT progress FROM books WHERE id='b'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT next_revision FROM reading_progress_deleted_generations WHERE book_id='b'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void observedTombstonePermitsOneRecreationButRejectsStaleOrRepeatedToken() {
        SQLiteDataSource dataSource = dataSource("tombstone-cas.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("INSERT INTO books(id,progress) VALUES('b',0)");
        SqliteReadingProgressRepository first = repository(dataSource, jdbc);
        SqliteReadingProgressRepository second = repository(dataSource, jdbc);
        ReadingProgressDto initial = progress("b", "1:10:0:0", 10);
        initial.setRevision(-1L);
        first.save(initial);
        first.deleteByBookId("b");

        long observedTombstone = first.revisionWhenAbsent("b");
        assertThat(observedTombstone).isEqualTo(-3L);
        ReadingProgressDto next = progress("b", "audio:4500:0:0", 45);
        next.setRevision(observedTombstone);
        first.save(next);
        assertThat(jdbc.queryForObject("SELECT revision FROM reading_progress WHERE book_id='b'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT progress FROM books WHERE id='b'", Integer.class))
                .isEqualTo(45);

        ReadingProgressDto secondStale = progress("b", "1:98:0:0", 98);
        secondStale.setRevision(observedTombstone);
        assertThrows(ReadingProgressRevisionConflictException.class, () -> second.save(secondStale));
        assertThat(first.findByBookId("b").orElseThrow().getAudioAnchorId()).isEqualTo("audio:4500:0:0");
        assertThat(jdbc.queryForObject("SELECT progress FROM books WHERE id='b'", Integer.class))
                .isEqualTo(45);

        first.deleteByBookId("b");
        assertThat(first.revisionWhenAbsent("b")).isEqualTo(-4L);
        assertThrows(ReadingProgressRevisionConflictException.class, () -> second.save(secondStale));
        assertThat(jdbc.queryForObject("SELECT progress FROM books WHERE id='b'", Integer.class))
                .isZero();
    }

    @Test
    void failedRecreationRestoresTombstoneAndBookSummary() {
        SQLiteDataSource dataSource = dataSource("tombstone-rollback.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("INSERT INTO books(id,progress) VALUES('b',0)");
        SqliteReadingProgressRepository repository = repository(dataSource, jdbc);
        var original = progress("b", "1:10:0:0", 10);
        original.setRevision(-1L);
        repository.save(original);
        repository.deleteByBookId("b");
        long tombstone = repository.revisionWhenAbsent("b");
        jdbc.execute("""
                CREATE TRIGGER reject_summary_before_commit BEFORE UPDATE OF progress ON books
                WHEN NEW.progress=35 BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END
                """);
        var attempted = progress("b", "1:35:0:0", 35);
        attempted.setRevision(tombstone);
        assertThrows(RuntimeException.class, () -> repository.save(attempted));
        assertThat(repository.revisionWhenAbsent("b")).isEqualTo(tombstone);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reading_progress WHERE book_id='b'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT progress FROM books WHERE id='b'", Integer.class))
                .isZero();
    }

    private static void createDeleteGenerationTriggers(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE reading_progress_deleted_generations (book_id TEXT PRIMARY KEY, next_revision INTEGER NOT NULL)");
        jdbc.execute("""
                CREATE TRIGGER reading_progress_record_deleted_generation AFTER DELETE ON reading_progress
                BEGIN
                    INSERT INTO reading_progress_deleted_generations(book_id,next_revision)
                    VALUES(OLD.book_id,OLD.revision+1)
                    ON CONFLICT(book_id) DO UPDATE SET
                      next_revision=MAX(reading_progress_deleted_generations.next_revision,excluded.next_revision);
                END
                """);
        jdbc.execute("""
                CREATE TRIGGER reading_progress_apply_deleted_generation AFTER INSERT ON reading_progress
                WHEN EXISTS(SELECT 1 FROM reading_progress_deleted_generations WHERE book_id=NEW.book_id)
                BEGIN
                    UPDATE reading_progress SET revision=MAX(revision,
                      (SELECT next_revision FROM reading_progress_deleted_generations WHERE book_id=NEW.book_id))
                    WHERE book_id=NEW.book_id;
                    DELETE FROM reading_progress_deleted_generations WHERE book_id=NEW.book_id;
                END
                """);
    }

    private SqliteReadingProgressRepository repository(SQLiteDataSource dataSource, JdbcTemplate jdbc) {
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentDataSource()).thenReturn(dataSource);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        var config = new CollectionTransactionConfig();
        var transactionTemplate = config.collectionTransactionTemplate(
                config.collectionTransactionManager(manager));
        return new SqliteReadingProgressRepository(manager, new SqliteBusyRetryExecutor(), transactionTemplate);
    }

    private SQLiteDataSource dataSource(String name) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temporary.resolve(name));
        return dataSource;
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE books(
                    id TEXT PRIMARY KEY,
                    progress INTEGER NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE reading_progress(
                    book_id TEXT PRIMARY KEY,
                    anchor_id TEXT,
                    text_anchor_id TEXT,
                    audio_anchor_id TEXT,
                    paragraph_index INTEGER DEFAULT 0,
                    paragraph_id TEXT NOT NULL DEFAULT '',
                    char_offset INTEGER NOT NULL DEFAULT 0,
                    percent REAL NOT NULL DEFAULT 0,
                    chapter_title TEXT NOT NULL DEFAULT '',
                    chapter_id TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL,
                    reading_time_seconds INTEGER NOT NULL DEFAULT 0,
                    last_device TEXT NOT NULL DEFAULT 'desktop', revision INTEGER NOT NULL DEFAULT 0
                )
                """);
        createDeleteGenerationTriggers(jdbc);
    }

    private static ReadingProgressDto progress(String bookId, String anchor, double percent) {
        return ReadingProgressDto.builder()
                .bookId(bookId)
                .anchorId(anchor)
                .paragraphIndex(4)
                .paragraphId("p4")
                .charOffset(8)
                .percent(percent)
                .chapterTitle("Chapter")
                .chapterId("chapter-4")
                .updatedAt(LocalDateTime.of(2026, 10, 16, 12, 0))
                .readingTimeSeconds(30)
                .lastDevice("desktop")
                .build();
    }
}
