package com.myhomelibcorp.infrastructure.persistence.sqlite;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Flyway+SQLite regression for the progress-revision delete/recreate ABA problem. */
class ReadingProgressDeleteGenerationMigrationTest {
    @TempDir Path temp;

    @Test
    void migratedSchemaRejectsRevisionObservedBeforeDeleteAndRecreation() {
        SQLiteDataSource ds = database("revision-aba.db");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO books(id,title,file_name,progress) VALUES('book-1','Book','b.fb2',0)");
        jdbc.update("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,updated_at,revision) "
                + "VALUES('book-1','',0,'2026-10-17',0)");
        long before = jdbc.queryForObject(
                "SELECT revision FROM reading_progress WHERE book_id='book-1'", Long.class);
        jdbc.update("DELETE FROM reading_progress WHERE book_id='book-1'");
        jdbc.update("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,updated_at,revision) "
                + "VALUES('book-1','',0,'2026-10-17',0)");
        long after = jdbc.queryForObject(
                "SELECT revision FROM reading_progress WHERE book_id='book-1'", Long.class);
        assertThat(after).isGreaterThan(before);
        assertThat(jdbc.update("UPDATE reading_progress SET anchor_id='stale',revision=revision+1 "
                + "WHERE book_id='book-1' AND revision=?", before)).isZero();
        assertThat(jdbc.queryForObject("SELECT anchor_id FROM reading_progress WHERE book_id='book-1'", String.class))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reading_progress_deleted_generations", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("PRAGMA integrity_check", String.class)).isEqualTo("ok");
    }

    @Test
    void deletionLedgerIsRetainedUntilNextInsertion() {
        SQLiteDataSource ds = database("revision-ledger.db");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,updated_at,revision) "
                + "VALUES('book-1','',0,'2026-10-17',7)");
        jdbc.update("DELETE FROM reading_progress WHERE book_id='book-1'");
        assertThat(jdbc.queryForObject("SELECT next_revision FROM reading_progress_deleted_generations "
                + "WHERE book_id='book-1'", Long.class)).isEqualTo(8L);
        jdbc.update("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,updated_at,revision) "
                + "VALUES('book-1','',0,'2026-10-17',0)");
        assertThat(jdbc.queryForObject("SELECT revision FROM reading_progress WHERE book_id='book-1'", Long.class))
                .isEqualTo(8L);
    }

    private SQLiteDataSource database(String name) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temp.resolve(name));
        return dataSource;
    }
}
