package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.book.BookArtifactState;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.cache.BookCache;
import com.myhomelibcorp.infrastructure.persistence.sqlite.batch.BookBatchWriter;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SqlitePreferredArtifactSelectionTest {

    @TempDir
    Path tempDir;

    @Test
    void selectingPreferredArtifactPersistsPreferenceAndUpdatesLegacyOperationalProjection() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("preferred.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentDataSource(ds);
        manager.setCurrentJdbcTemplate(jdbc);

        SqliteBookCommandRepository repository = new SqliteBookCommandRepository(
                manager,
                mock(BookAuthorHelper.class),
                mock(BookGenreHelper.class),
                mock(BookBatchWriter.class),
                new BookCache(manager),
                new SqliteBusyRetryExecutor());

        String bookId = "44444444-4444-4444-4444-444444444444";
        jdbc.update("""
                INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,deleted,local,collection_root,format)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, bookId, "Selectable", "book.epub", "old", "", 1000, 0, 1, "/old", "epub");
        insertArtifact(jdbc, bookId, "a-epub", "book.epub", "epub", "/library", "epub", 1000, 1, "AVAILABLE");
        insertArtifact(jdbc, bookId, "a-pdf", "book.pdf", "pdf", "/library", "pdf", 2000, 1, "AVAILABLE");
        jdbc.update("INSERT INTO book_artifact_preferences(book_id,preferred_artifact_id) VALUES (?,?)", bookId, "a-epub");

        repository.selectPreferredArtifact(BookId.fromString(bookId), "a-pdf");

        assertThat(jdbc.queryForObject("SELECT preferred_artifact_id FROM book_artifact_preferences WHERE book_id=?", String.class, bookId))
                .isEqualTo("a-pdf");
        assertThat(jdbc.queryForObject("SELECT file_name FROM books WHERE id=?", String.class, bookId)).isEqualTo("book.pdf");
        assertThat(jdbc.queryForObject("SELECT folder FROM books WHERE id=?", String.class, bookId)).isEqualTo("pdf");
        assertThat(jdbc.queryForObject("SELECT collection_root FROM books WHERE id=?", String.class, bookId)).isEqualTo("/library");
        assertThat(jdbc.queryForObject("SELECT file_size FROM books WHERE id=?", Long.class, bookId)).isEqualTo(2000L);
        assertThat(jdbc.queryForObject("SELECT format FROM books WHERE id=?", String.class, bookId)).isEqualTo("pdf");

        assertThatThrownBy(() -> repository.selectPreferredArtifact(BookId.fromString(bookId), "foreign-artifact"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void upsertArtifactPersistsMetadataAndCanPromotePreferredProjection() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("upsert-artifact.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentDataSource(ds);
        manager.setCurrentJdbcTemplate(jdbc);
        SqliteBookCommandRepository repository = new SqliteBookCommandRepository(
                manager, mock(BookAuthorHelper.class), mock(BookGenreHelper.class), mock(BookBatchWriter.class),
                new BookCache(manager), new SqliteBusyRetryExecutor());

        String bookId = "55555555-5555-5555-5555-555555555555";
        jdbc.update("""
                INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,deleted,local,collection_root,format)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, bookId, "Convertible", "source.fb2", "", "", 100, 0, 1, "/source", "fb2");

        BookArtifact artifact = BookArtifact.builder()
                .id("conversion:test")
                .sourceId("conversion:mock")
                .name("converted.epub")
                .mediaType("application/epub+zip")
                .format("epub")
                .file(new BookFile("converted.epub", "", "", 321, "/converted"))
                .sha256("abc123")
                .contentFingerprint("abc123")
                .local(true)
                .remote(false)
                .state(BookArtifactState.AVAILABLE)
                .metadata(Map.of("conversion.provider", "mock", "conversion.sourceFormat", "fb2"))
                .build();

        repository.upsertArtifact(BookId.fromString(bookId), artifact, true);

        assertThat(jdbc.queryForObject("SELECT file_format FROM book_artifacts WHERE artifact_id=?", String.class, artifact.getId()))
                .isEqualTo("epub");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_artifact_metadata WHERE artifact_id=?", Integer.class, artifact.getId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT preferred_artifact_id FROM book_artifact_preferences WHERE book_id=?", String.class, bookId))
                .isEqualTo(artifact.getId());
        assertThat(jdbc.queryForObject("SELECT file_name FROM books WHERE id=?", String.class, bookId)).isEqualTo("converted.epub");
        assertThat(jdbc.queryForObject("SELECT collection_root FROM books WHERE id=?", String.class, bookId)).isEqualTo("/converted");
        assertThat(jdbc.queryForObject("SELECT format FROM books WHERE id=?", String.class, bookId)).isEqualTo("epub");
    }

    private static void insertArtifact(JdbcTemplate jdbc, String bookId, String artifactId, String fileName,
                                       String format, String root, String folder, long size, int local, String state) {
        jdbc.update("""
                INSERT INTO book_artifacts(artifact_id,book_id,artifact_name,file_format,file_name,size_bytes,
                                           remote,local,collection_root,folder,state)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, artifactId, bookId, fileName, format, fileName, size, 0, local, root, folder, state);
    }
}
