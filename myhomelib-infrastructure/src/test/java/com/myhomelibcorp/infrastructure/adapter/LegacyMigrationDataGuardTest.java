package com.myhomelibcorp.infrastructure.adapter;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyMigrationDataGuardTest {

    @TempDir
    Path tempDir;

    private final LegacyMigrationDataGuard guard = new LegacyMigrationDataGuard();

    @Test
    void v1UpgradePreservesAuthorsThatShareFirstAndLastNameButDifferByMiddleName() {
        var ds = dataSource(tempDir.resolve("v1-authors.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        migrateTo(ds, 1);

        jdbc.update("INSERT INTO authors(id,first_name,middle_name,last_name) VALUES ('a1','Alex','One','Smith')");
        jdbc.update("INSERT INTO authors(id,first_name,middle_name,last_name) VALUES ('a2','Alex','Two','Smith')");
        insertBook(jdbc, "b1", "Book One");
        insertBook(jdbc, "b2", "Book Two");
        jdbc.update("INSERT INTO book_authors(book_id,author_id) VALUES ('b1','a1')");
        jdbc.update("INSERT INTO book_authors(book_id,author_id) VALUES ('b2','a2')");

        guard.captureBeforeMigrate(ds, "1");
        migrateToLatest(ds);
        guard.restoreAfterMigrate(ds);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM authors WHERE first_name='Alex' AND last_name='Smith'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT middle_name FROM authors WHERE id='a1'", String.class)).isEqualTo("One");
        assertThat(jdbc.queryForObject("SELECT middle_name FROM authors WHERE id='a2'", String.class)).isEqualTo("Two");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_authors WHERE book_id='b1' AND author_id='a1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_authors WHERE book_id='b2' AND author_id='a2'", Integer.class))
                .isEqualTo(1);
        assertThat(tableExists(jdbc, LegacyMigrationDataGuard.AUTHOR_GUARD)).isFalse();
        assertThat(tableExists(jdbc, LegacyMigrationDataGuard.AUTHOR_LINK_GUARD)).isFalse();
    }

    @Test
    void durableAuthorGuardSurvivesIntermediateMigrationAndCanResumeLater() {
        var ds = dataSource(tempDir.resolve("resume-authors.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        migrateTo(ds, 1);

        jdbc.update("INSERT INTO authors(id,first_name,middle_name,last_name) VALUES ('a1','Chris','A','Lee')");
        jdbc.update("INSERT INTO authors(id,first_name,middle_name,last_name) VALUES ('a2','Chris','B','Lee')");
        insertBook(jdbc, "b1", "One");
        insertBook(jdbc, "b2", "Two");
        jdbc.update("INSERT INTO book_authors(book_id,author_id) VALUES ('b1','a1')");
        jdbc.update("INSERT INTO book_authors(book_id,author_id) VALUES ('b2','a2')");

        guard.captureBeforeMigrate(ds, "1");
        migrateTo(ds, 10); // V7 has already collapsed the pair at this point.

        assertThat(tableExists(jdbc, LegacyMigrationDataGuard.AUTHOR_GUARD)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM authors WHERE first_name='Chris' AND last_name='Lee'", Integer.class))
                .isEqualTo(1);

        migrateToLatest(ds);
        new LegacyMigrationDataGuard().restoreAfterMigrate(ds);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM authors WHERE first_name='Chris' AND last_name='Lee'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_authors WHERE book_id IN ('b1','b2')", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void v25UpgradePreservesChapterAndReadingTimeFieldsLostByHistoricalV26Script() {
        var ds = dataSource(tempDir.resolve("v25-reading.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        migrateTo(ds, 25);

        insertBook(jdbc, "reader-book", "Reader Book");
        jdbc.update("""
                INSERT INTO reading_progress(
                    book_id,paragraph_id,char_offset,percent,chapter_title,chapter_id,updated_at,reading_time_seconds
                ) VALUES ('reader-book','p17',9,42.5,'Important Chapter','chapter-17','2026-09-04T12:00:00Z',987)
                """);

        guard.captureBeforeMigrate(ds, "25");
        migrateToLatest(ds);
        guard.restoreAfterMigrate(ds);

        assertThat(jdbc.queryForObject("SELECT chapter_title FROM reading_progress WHERE book_id='reader-book'", String.class))
                .isEqualTo("Important Chapter");
        assertThat(jdbc.queryForObject("SELECT chapter_id FROM reading_progress WHERE book_id='reader-book'", String.class))
                .isEqualTo("chapter-17");
        assertThat(jdbc.queryForObject("SELECT reading_time_seconds FROM reading_progress WHERE book_id='reader-book'", Long.class))
                .isEqualTo(987L);
        assertThat(jdbc.queryForObject("SELECT anchor_id FROM reading_progress WHERE book_id='reader-book'", String.class))
                .isEqualTo("p17");
        assertThat(tableExists(jdbc, LegacyMigrationDataGuard.READING_GUARD)).isFalse();
    }

    private static DriverManagerDataSource dataSource(Path db) {
        return new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
    }

    private static void migrateTo(DriverManagerDataSource ds, int version) {
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(Integer.toString(version)))
                .load()
                .migrate();
    }

    private static void migrateToLatest(DriverManagerDataSource ds) {
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static void insertBook(JdbcTemplate jdbc, String id, String title) {
        jdbc.update("""
                INSERT INTO books(id,title,series,sequence_number,file_name,folder,archive_entry,language,file_size,
                                  keywords,annotation,rate,progress,update_date,isbn,deleted,local)
                VALUES (?, ?, NULL, NULL, ?, '', '', 'en', 1, '', '', 0, 0, '', '', 0, 1)
                """, id, title, id + ".fb2");
    }

    private static boolean tableExists(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, table);
        return count != null && count > 0;
    }
}
