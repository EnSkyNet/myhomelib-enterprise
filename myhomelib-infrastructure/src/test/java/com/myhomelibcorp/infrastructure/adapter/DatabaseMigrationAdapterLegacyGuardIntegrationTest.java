package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.KeywordIndexBackfillService;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteAuthorRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DatabaseMigrationAdapterLegacyGuardIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void adapterProtectsPreV7DuplicateNameAuthorsWithoutChangingHistoricalFlywayChecksums() {
        DriverManagerDataSource ds = dataSource(tempDir.resolve("adapter-v1.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        migrateTo(ds, 1);
        jdbc.update("INSERT INTO authors(id,first_name,middle_name,last_name) VALUES ('a1','Dana','One','Stone')");
        jdbc.update("INSERT INTO authors(id,first_name,middle_name,last_name) VALUES ('a2','Dana','Two','Stone')");
        insertBook(jdbc, "b1");
        insertBook(jdbc, "b2");
        jdbc.update("INSERT INTO book_authors(book_id,author_id) VALUES ('b1','a1')");
        jdbc.update("INSERT INTO book_authors(book_id,author_id) VALUES ('b2','a2')");

        int pendingBefore = flyway(ds).info().pending().length;
        DatabaseMigrationAdapter adapter = adapter(ds);
        assertThat(adapter.migrateCurrentCollection()).isEqualTo(pendingBefore);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM authors WHERE first_name='Dana' AND last_name='Stone'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_authors WHERE book_id IN ('b1','b2')", Integer.class))
                .isEqualTo(2);
        assertThat(flyway(ds).info().pending()).isEmpty();
    }

    @Test
    void adapterProtectsV25ReaderFieldsAcrossHistoricalV26Recreation() {
        DriverManagerDataSource ds = dataSource(tempDir.resolve("adapter-v25.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        migrateTo(ds, 25);
        insertBook(jdbc, "reader");
        jdbc.update("""
                INSERT INTO reading_progress(
                    book_id,paragraph_id,char_offset,percent,chapter_title,chapter_id,updated_at,reading_time_seconds
                ) VALUES ('reader','p5',3,12.0,'Chapter Five','ch5','2026-09-04T00:00:00Z',1234)
                """);

        int pendingBefore = flyway(ds).info().pending().length;
        DatabaseMigrationAdapter adapter = adapter(ds);
        assertThat(adapter.migrateCurrentCollection()).isEqualTo(pendingBefore);

        assertThat(jdbc.queryForObject("SELECT chapter_title FROM reading_progress WHERE book_id='reader'", String.class))
                .isEqualTo("Chapter Five");
        assertThat(jdbc.queryForObject("SELECT chapter_id FROM reading_progress WHERE book_id='reader'", String.class))
                .isEqualTo("ch5");
        assertThat(jdbc.queryForObject("SELECT reading_time_seconds FROM reading_progress WHERE book_id='reader'", Long.class))
                .isEqualTo(1234L);
    }

    private static DatabaseMigrationAdapter adapter(DriverManagerDataSource ds) {
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentDataSource()).thenReturn(ds);
        SqliteAuthorRepository authors = mock(SqliteAuthorRepository.class);
        KeywordIndexBackfillService keywords = mock(KeywordIndexBackfillService.class);
        return new DatabaseMigrationAdapter(manager, authors, keywords, new LegacyMigrationDataGuard());
    }

    private static DriverManagerDataSource dataSource(Path db) {
        return new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
    }

    private static void migrateTo(DriverManagerDataSource ds, int version) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(Integer.toString(version))).load().migrate();
    }

    private static Flyway flyway(DriverManagerDataSource ds) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
    }

    private static void insertBook(JdbcTemplate jdbc, String id) {
        jdbc.update("""
                INSERT INTO books(id,title,series,sequence_number,file_name,folder,archive_entry,language,file_size,
                                  keywords,annotation,rate,progress,update_date,isbn,deleted,local)
                VALUES (?, ?, NULL, NULL, ?, '', '', 'en', 1, '', '', 0, 0, '', '', 0, 1)
                """, id, "Book " + id, id + ".fb2");
    }
}
