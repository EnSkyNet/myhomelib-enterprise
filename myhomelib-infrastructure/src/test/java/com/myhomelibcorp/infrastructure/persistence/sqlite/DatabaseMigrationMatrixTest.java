package com.myhomelibcorp.infrastructure.persistence.sqlite;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Release-acceptance matrix: representative historical schemas must reach V49 without data loss. */
class DatabaseMigrationMatrixTest {

    private static final List<Integer> SOURCE_VERSIONS = List.of(1, 10, 20, 30, 40, 44);

    @TempDir
    Path tempDir;

    @Test
    void migratesRepresentativeHistoricalSchemasToV49AndPreservesCoreAndUserData() {
        for (int sourceVersion : SOURCE_VERSIONS) {
            Path db = tempDir.resolve("migration-v" + sourceVersion + ".db");
            var ds = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion(Integer.toString(sourceVersion)))
                    .load()
                    .migrate();

            seedV1CompatibleData(jdbc, sourceVersion);

            Flyway flyway = Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .load();
            flyway.migrate();

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("49");
            assertThat(jdbc.queryForObject("SELECT title FROM books WHERE id='book-matrix'", String.class))
                    .isEqualTo("Migration Matrix Book V" + sourceVersion);
            assertThat(jdbc.queryForObject("SELECT keywords FROM books WHERE id='book-matrix'", String.class))
                    .isEqualTo("Alpha; Beta");
            assertThat(jdbc.queryForObject("SELECT rate FROM books WHERE id='book-matrix'", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT progress FROM books WHERE id='book-matrix'", Integer.class)).isEqualTo(33);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_authors WHERE book_id='book-matrix' AND author_id='author-matrix'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_genres WHERE book_id='book-matrix' AND genre_code='sf'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT value FROM settings WHERE key='matrix.user.setting'", String.class)).isEqualTo("preserved");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_groups bg JOIN groups g ON g.id=bg.group_id WHERE bg.book_id='book-matrix' AND g.name='Matrix Group'", Integer.class)).isEqualTo(1);

            assertIndex(jdbc, "idx_authors_navigation_page");
            assertIndex(jdbc, "idx_books_active_language_title");
            assertIndex(jdbc, "idx_books_active_id");
            assertIndex(jdbc, "idx_keyword_books_book_id");
        }
    }

    private static void seedV1CompatibleData(JdbcTemplate jdbc, int sourceVersion) {
        jdbc.update("INSERT INTO authors(id, first_name, middle_name, last_name) VALUES ('author-matrix','Dmytro','','Dornichev')");
        jdbc.update("INSERT INTO genres(code,name,parent_code,fb2_code) VALUES ('sf','Science Fiction',NULL,'sf')");
        jdbc.update("""
                INSERT INTO books(id,title,series,sequence_number,file_name,folder,archive_entry,language,file_size,keywords,annotation,rate,progress,update_date,isbn,deleted,local)
                VALUES ('book-matrix', ?, 'Matrix Series', 1, 'matrix.fb2', '', '', 'uk', 1234, 'Alpha; Beta', 'annotation', 4, 33, '2026-09-04', '', 0, 1)
                """, "Migration Matrix Book V" + sourceVersion);
        jdbc.update("INSERT INTO book_authors(book_id,author_id) VALUES ('book-matrix','author-matrix')");
        jdbc.update("INSERT INTO book_genres(book_id,genre_code) VALUES ('book-matrix','sf')");
        jdbc.update("INSERT OR REPLACE INTO settings(key,value) VALUES ('matrix.user.setting','preserved')");
        jdbc.update("INSERT OR IGNORE INTO groups(name,allow_delete) VALUES ('Matrix Group',1)");
        Integer groupId = jdbc.queryForObject("SELECT id FROM groups WHERE name='Matrix Group'", Integer.class);
        jdbc.update("INSERT INTO book_groups(book_id,group_id) VALUES ('book-matrix',?)", groupId);
    }

    private static void assertIndex(JdbcTemplate jdbc, String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?", Integer.class, name);
        assertThat(count).as("index %s", name).isEqualTo(1);
    }
}
