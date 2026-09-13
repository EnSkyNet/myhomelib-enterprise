package com.myhomelibcorp.infrastructure.persistence.sqlite;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;

class MultiArtifactMigrationV50Test {

    private static final int BOOKS = 10_000;
    private static final int ARTIFACTS_PER_BOOK = 3;

    @TempDir
    Path tempDir;

    @Test
    void migratesLargeV49DatabaseWithoutLosingBooksOrArtifactsAndCreatesPreferences() throws Exception {
        Path db = tempDir.resolve("multi-artifact-v49.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());

        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("49"))
                .load()
                .migrate();

        seedLargeV49Dataset(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertThat(count(jdbc, "books")).isEqualTo(BOOKS);
        assertThat(count(jdbc, "book_artifacts")).isEqualTo(BOOKS * ARTIFACTS_PER_BOOK);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("50"))
                .load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("50");
        assertThat(count(jdbc, "books")).as("logical book row count").isEqualTo(BOOKS);
        assertThat(count(jdbc, "book_artifacts")).as("artifact row count").isEqualTo(BOOKS * ARTIFACTS_PER_BOOK);
        assertThat(count(jdbc, "book_artifact_preferences")).as("one preferred artifact per book").isEqualTo(BOOKS);

        assertThat(jdbc.queryForObject("SELECT preferred_artifact_id FROM book_artifact_preferences WHERE book_id='book-42'", String.class))
                .isEqualTo("book-42:epub");
        assertThat(jdbc.queryForObject("SELECT state FROM book_artifacts WHERE artifact_id='book-42:epub'", String.class))
                .isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT state FROM book_artifacts WHERE artifact_id='book-42:fb2'", String.class))
                .isEqualTo("REMOTE_ONLY");
        assertThat(jdbc.queryForObject("SELECT state FROM book_artifacts WHERE artifact_id='book-42:pdf'", String.class))
                .isEqualTo("MISSING");

        jdbc.update("DELETE FROM book_artifacts WHERE artifact_id='book-42:pdf'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM books WHERE id='book-42'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_artifacts WHERE book_id='book-42'", Integer.class)).isEqualTo(2);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private static void seedLargeV49Dataset(DriverManagerDataSource ds) throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement book = c.prepareStatement("""
                    INSERT INTO books(id,title,file_name,folder,archive_entry,language,file_size,keywords,annotation,
                                      rate,progress,update_date,isbn,deleted,local,collection_root,format,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """);
                 PreparedStatement artifact = c.prepareStatement("""
                    INSERT INTO book_artifacts(artifact_id,book_id,source_id,artifact_name,media_type,file_format,
                                               file_name,archive_name,archive_entry,size_bytes,remote,local)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                for (int i = 0; i < BOOKS; i++) {
                    String bookId = "book-" + i;
                    String epub = bookId + ".epub";
                    book.setString(1, bookId);
                    book.setString(2, "Book " + i);
                    book.setString(3, epub);
                    book.setString(4, "books");
                    book.setString(5, "");
                    book.setString(6, "uk");
                    book.setLong(7, 1_000 + i);
                    book.setString(8, "migration;multi-artifact");
                    book.setString(9, "V50 migration acceptance");
                    book.setInt(10, 0);
                    book.setInt(11, 0);
                    book.setString(12, "2026-09-07T00:00:00");
                    book.setString(13, "");
                    book.setInt(14, 0);
                    book.setInt(15, 1);
                    book.setString(16, "/library");
                    book.setString(17, "epub");
                    book.setString(18, "2026-09-07T00:00:00");
                    book.addBatch();

                    addArtifact(artifact, bookId, "epub", epub, "application/epub+zip", 0, 1);
                    addArtifact(artifact, bookId, "fb2", bookId + ".fb2", "application/fb2+xml", 1, 0);
                    addArtifact(artifact, bookId, "pdf", bookId + ".pdf", "application/pdf", 0, 0);

                    if ((i + 1) % 500 == 0) {
                        book.executeBatch();
                        artifact.executeBatch();
                    }
                }
                book.executeBatch();
                artifact.executeBatch();
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static void addArtifact(PreparedStatement ps, String bookId, String format, String fileName,
                                    String mediaType, int remote, int local) throws Exception {
        ps.setString(1, bookId + ":" + format);
        ps.setString(2, bookId);
        ps.setString(3, "source-large");
        ps.setString(4, fileName);
        ps.setString(5, mediaType);
        ps.setString(6, format);
        ps.setString(7, fileName);
        ps.setString(8, null);
        ps.setString(9, "");
        ps.setLong(10, 1_000);
        ps.setInt(11, remote);
        ps.setInt(12, local);
        ps.addBatch();
    }
}
