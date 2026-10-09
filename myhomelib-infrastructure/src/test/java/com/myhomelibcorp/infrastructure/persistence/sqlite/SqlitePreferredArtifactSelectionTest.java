package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.book.BookArtifactState;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.cache.BookCache;
import com.myhomelibcorp.infrastructure.persistence.sqlite.batch.BookBatchWriter;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookArtifactHelper;
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
        assertThat(jdbc.queryForObject("SELECT format FROM books WHERE id=?", String.class, bookId)).isEqualTo("PDF");

        insertArtifact(jdbc, bookId, "a-7z", "book.7z", "7z", "/library", "archives", 3000, 1, "AVAILABLE");
        repository.selectPreferredArtifact(BookId.fromString(bookId), "a-7z");
        assertThat(jdbc.queryForObject("SELECT file_name FROM books WHERE id=?", String.class, bookId)).isEqualTo("book.7z");
        assertThat(jdbc.queryForObject("SELECT format FROM books WHERE id=?", String.class, bookId)).isEqualTo("SEVEN_Z");

        assertThatThrownBy(() -> repository.selectPreferredArtifact(BookId.fromString(bookId), "foreign-artifact"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void selectingPreferredArtifactRollsBackPreferenceWhenLegacyProjectionFails() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("preferred-rollback.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentDataSource(ds);
        manager.setCurrentJdbcTemplate(jdbc);
        SqliteBookCommandRepository repository = new SqliteBookCommandRepository(
                manager, mock(BookAuthorHelper.class), mock(BookGenreHelper.class), mock(BookBatchWriter.class),
                new BookCache(manager), new SqliteBusyRetryExecutor());

        String bookId = "66666666-6666-6666-6666-666666666666";
        jdbc.update("""
                INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,deleted,local,collection_root,format)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, bookId, "Rollback", "book.epub", "old", "", 1000, 0, 1, "/old", "epub");
        insertArtifact(jdbc, bookId, "a-epub", "book.epub", "epub", "/library", "epub", 1000, 1, "AVAILABLE");
        insertArtifact(jdbc, bookId, "a-pdf", "book.pdf", "pdf", "/library", "pdf", 2000, 1, "AVAILABLE");
        jdbc.update("INSERT INTO book_artifact_preferences(book_id,preferred_artifact_id) VALUES (?,?)", bookId, "a-epub");
        jdbc.execute("""
                CREATE TRIGGER fail_preferred_projection
                BEFORE UPDATE ON books
                WHEN OLD.id = '66666666-6666-6666-6666-666666666666'
                BEGIN
                    SELECT RAISE(ABORT, 'projection failure');
                END
                """);

        assertThatThrownBy(() -> repository.selectPreferredArtifact(BookId.fromString(bookId), "a-pdf"))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
                "SELECT preferred_artifact_id FROM book_artifact_preferences WHERE book_id=?", String.class, bookId))
                .isEqualTo("a-epub");
        assertThat(jdbc.queryForObject("SELECT file_name FROM books WHERE id=?", String.class, bookId))
                .isEqualTo("book.epub");
    }


    @Test
    void secondaryConversionCreatesMissingPreferenceForExistingOriginalAndDoesNotRewriteProjection() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("secondary-preserves-original.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentDataSource(ds);
        manager.setCurrentJdbcTemplate(jdbc);
        SqliteBookCommandRepository repository = new SqliteBookCommandRepository(
                manager, mock(BookAuthorHelper.class), mock(BookGenreHelper.class), mock(BookBatchWriter.class),
                new BookCache(manager), new SqliteBusyRetryExecutor());

        String bookId = "77777777-7777-7777-7777-777777777777";
        jdbc.update("""
                INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,deleted,local,collection_root,format)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, bookId, "Keep original", "source.zip", "books", "source.fb2", 100, 0, 1, "/library", "fb2");
        insertArtifact(jdbc, bookId, "z-original", "source.zip", "fb2", "/library", "books", 100, 1, "AVAILABLE");
        jdbc.update("UPDATE book_artifacts SET archive_name='source.zip', archive_entry='source.fb2', source_id='inpx:test' WHERE artifact_id='z-original'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_artifact_preferences WHERE book_id=?", Integer.class, bookId)).isZero();

        BookArtifact conversion = BookArtifact.builder()
                .id("a-conversion")
                .sourceId("conversion:calibre-cli:epub")
                .name("converted.epub")
                .mediaType("application/epub+zip")
                .format("epub")
                .file(new BookFile("converted.epub", "", "", 321, "/converted"))
                .sha256("abc123")
                .contentFingerprint("abc123")
                .local(true)
                .remote(false)
                .state(BookArtifactState.AVAILABLE)
                .metadata(Map.of("conversion.provider", "calibre-cli:epub", "conversion.sourceArtifactId", "z-original"))
                .build();

        repository.upsertArtifact(BookId.fromString(bookId), conversion, false);

        assertThat(jdbc.queryForObject("SELECT preferred_artifact_id FROM book_artifact_preferences WHERE book_id=?", String.class, bookId))
                .isEqualTo("z-original");
        assertThat(jdbc.queryForObject("SELECT file_name FROM books WHERE id=?", String.class, bookId)).isEqualTo("source.zip");
        assertThat(jdbc.queryForObject("SELECT folder FROM books WHERE id=?", String.class, bookId)).isEqualTo("books");
        assertThat(jdbc.queryForObject("SELECT archive_entry FROM books WHERE id=?", String.class, bookId)).isEqualTo("source.fb2");
        assertThat(jdbc.queryForObject("SELECT collection_root FROM books WHERE id=?", String.class, bookId)).isEqualTo("/library");
        assertThat(jdbc.queryForObject("SELECT format FROM books WHERE id=?", String.class, bookId)).isEqualTo("fb2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_artifacts WHERE book_id=?", Integer.class, bookId)).isEqualTo(2);

        // Critical regression boundary: re-read exactly as production hydration does.
        // The generated conversion sorts before the source artifact by id, but must still
        // not become Book.file/preferred after the SQLite round-trip.
        var raw = jdbc.queryForObject("SELECT * FROM books WHERE id=?", new BookRowMapper(), bookId);
        var rehydrated = new BookArtifactHelper(manager).attachArtifacts(java.util.List.of(raw)).getFirst();
        assertThat(rehydrated.getPreferredArtifactId()).isEqualTo("z-original");
        assertThat(rehydrated.getFileName()).isEqualTo("source.zip");
        assertThat(rehydrated.getArchiveEntry()).isEqualTo("source.fb2");
        assertThat(rehydrated.getCollectionRoot()).isEqualTo("/library");
    }

    @Test
    void secondaryConversionBackfillsMissingCanonicalSourceBeforeAddingGeneratedArtifact() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("secondary-backfills-source.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentDataSource(ds);
        manager.setCurrentJdbcTemplate(jdbc);
        SqliteBookCommandRepository repository = new SqliteBookCommandRepository(
                manager, mock(BookAuthorHelper.class), mock(BookGenreHelper.class), mock(BookBatchWriter.class),
                new BookCache(manager), new SqliteBusyRetryExecutor());

        String bookId = "88888888-8888-8888-8888-888888888888";
        jdbc.update("""
                INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,deleted,local,collection_root,format)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, bookId, "Legacy without artifact", "source.zip", "books", "source.fb2", 100, 0, 1, "/library", "fb2");

        BookArtifact conversion = BookArtifact.builder()
                .id("a-generated-epub")
                .sourceId("conversion:fbc:epub")
                .name("converted.epub")
                .mediaType("application/epub+zip")
                .format("epub")
                .file(new BookFile("converted.epub", "", "", 321, "/converted"))
                .local(true).remote(false).state(BookArtifactState.AVAILABLE)
                .metadata(Map.of("conversion.sourceFormat", "fb2"))
                .build();

        repository.upsertArtifact(BookId.fromString(bookId), conversion, false);

        assertThat(jdbc.queryForObject("SELECT preferred_artifact_id FROM book_artifact_preferences WHERE book_id=?", String.class, bookId))
                .isEqualTo("legacy:" + bookId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_artifacts WHERE book_id=?", Integer.class, bookId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT archive_name FROM book_artifacts WHERE artifact_id=?", String.class, "legacy:" + bookId))
                .isEqualTo("source.zip");
        assertThat(jdbc.queryForObject("SELECT file_name FROM book_artifacts WHERE artifact_id=?", String.class, "legacy:" + bookId))
                .isEqualTo("source.fb2");
        assertThat(jdbc.queryForObject("SELECT file_format FROM book_artifacts WHERE artifact_id=?", String.class, "legacy:" + bookId))
                .isEqualTo("FB2");
        assertThat(jdbc.queryForObject("SELECT file_name FROM books WHERE id=?", String.class, bookId)).isEqualTo("source.zip");

        var raw = jdbc.queryForObject("SELECT * FROM books WHERE id=?", new BookRowMapper(), bookId);
        var rehydrated = new BookArtifactHelper(manager).attachArtifacts(java.util.List.of(raw)).getFirst();
        assertThat(rehydrated.getPreferredArtifactId()).isEqualTo("legacy:" + bookId);
        assertThat(rehydrated.getFileName()).isEqualTo("source.zip");
        assertThat(rehydrated.getArchiveEntry()).isEqualTo("source.fb2");
    }

    @Test
    void updateStorageSynchronizesDownloadedArchiveArtifactAndPreservesLogicalFormat() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("download-storage-artifact.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentDataSource(ds);
        manager.setCurrentJdbcTemplate(jdbc);
        SqliteBookCommandRepository repository = new SqliteBookCommandRepository(
                manager, mock(BookAuthorHelper.class), mock(BookGenreHelper.class), mock(BookBatchWriter.class),
                new BookCache(manager), new SqliteBusyRetryExecutor());

        String bookId = "99999999-9999-9999-9999-999999999999";
        jdbc.update("""
                INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,deleted,local,collection_root,format)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, bookId, "Remote archive", "729586.fb2", "", "Dornichev.fb2", 100, 0, 0, "/remote", "fb2");
        insertArtifact(jdbc, bookId, "catalog-source", "Dornichev.fb2", "fb2", null, null, 100, 0, "REMOTE_ONLY");
        jdbc.update("""
                UPDATE book_artifacts
                   SET source_id='inpx:test', remote=1, archive_name='729586.zip', archive_entry='Dornichev.fb2'
                 WHERE artifact_id='catalog-source'
                """);

        repository.updateStorage(BookId.fromString(bookId), "/library", "Д", "729586 Демон #01-18.fb2.zip", "Dornichev.fb2", true);

        assertThat(jdbc.queryForObject("SELECT local FROM book_artifacts WHERE artifact_id='catalog-source'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM book_artifacts WHERE artifact_id='catalog-source'", String.class)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT collection_root FROM book_artifacts WHERE artifact_id='catalog-source'", String.class)).isEqualTo("/library");
        assertThat(jdbc.queryForObject("SELECT folder FROM book_artifacts WHERE artifact_id='catalog-source'", String.class)).isEqualTo("Д");
        assertThat(jdbc.queryForObject("SELECT archive_name FROM book_artifacts WHERE artifact_id='catalog-source'", String.class))
                .isEqualTo("729586 Демон #01-18.fb2.zip");
        assertThat(jdbc.queryForObject("SELECT file_name FROM book_artifacts WHERE artifact_id='catalog-source'", String.class))
                .isEqualTo("Dornichev.fb2");
        assertThat(jdbc.queryForObject("SELECT format FROM books WHERE id=?", String.class, bookId)).isEqualTo("ZIP");
        assertThat(jdbc.queryForObject("SELECT preferred_artifact_id FROM book_artifact_preferences WHERE book_id=?", String.class, bookId))
                .isEqualTo("catalog-source");

        var raw = jdbc.queryForObject("SELECT * FROM books WHERE id=?", new BookRowMapper(), bookId);
        var rehydrated = new BookArtifactHelper(manager).attachArtifacts(java.util.List.of(raw)).getFirst();
        assertThat(rehydrated.isLocal()).isTrue();
        assertThat(rehydrated.getFileName()).isEqualTo("729586 Демон #01-18.fb2.zip");
        assertThat(rehydrated.getArchiveEntry()).isEqualTo("Dornichev.fb2");
    }

    @Test
    void markStorageMissingAlsoInvalidatesProjectedArtifactAcrossRestart() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("missing-artifact-state.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentDataSource(ds);
        manager.setCurrentJdbcTemplate(jdbc);
        SqliteBookCommandRepository repository = new SqliteBookCommandRepository(
                manager, mock(BookAuthorHelper.class), mock(BookGenreHelper.class), mock(BookBatchWriter.class),
                new BookCache(manager), new SqliteBusyRetryExecutor());

        String bookId = "77777777-7777-7777-7777-777777777777";
        jdbc.update("""
                INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,deleted,local,collection_root,format)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, bookId, "Downloaded source", "source.fb2.zip", "books", "source.fb2", 100, 0, 1, "/library", "FB2");
        insertArtifact(jdbc, bookId, "catalog-source", "source.fb2", "FB2", "/library", "books", 100, 1, "AVAILABLE");
        jdbc.update("""
                UPDATE book_artifacts
                   SET source_id='inpx:test', remote=1, archive_name='source.fb2.zip', archive_entry='source.fb2'
                 WHERE artifact_id='catalog-source'
                """);
        jdbc.update("INSERT INTO book_artifact_preferences(book_id,preferred_artifact_id) VALUES (?,?)", bookId, "catalog-source");

        repository.markStorageMissing(BookId.fromString(bookId));

        assertThat(jdbc.queryForObject("SELECT local FROM books WHERE id=?", Integer.class, bookId)).isZero();
        assertThat(jdbc.queryForObject("SELECT missing_since IS NOT NULL FROM books WHERE id=?", Integer.class, bookId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT local FROM book_artifacts WHERE artifact_id='catalog-source'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM book_artifacts WHERE artifact_id='catalog-source'", String.class))
                .isEqualTo("REMOTE_ONLY");

        var raw = jdbc.queryForObject("SELECT * FROM books WHERE id=?", new BookRowMapper(), bookId);
        var rehydrated = new BookArtifactHelper(manager).attachArtifacts(java.util.List.of(raw)).getFirst();
        assertThat(rehydrated.isLocal()).isFalse();
        assertThat(rehydrated.getPreferredArtifact()).isPresent();
        assertThat(rehydrated.getPreferredArtifact().orElseThrow().isLocal()).isFalse();
        assertThat(rehydrated.getPreferredArtifact().orElseThrow().getState()).isEqualTo(BookArtifactState.REMOTE_ONLY);
    }

    @Test
    void storageBackfillPreservesRemoteCatalogSemanticsWhenArtifactRowIsMissing() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("remote-backfill.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentDataSource(ds);
        manager.setCurrentJdbcTemplate(jdbc);
        SqliteBookCommandRepository repository = new SqliteBookCommandRepository(
                manager, mock(BookAuthorHelper.class), mock(BookGenreHelper.class), mock(BookBatchWriter.class),
                new BookCache(manager), new SqliteBusyRetryExecutor());

        String bookId = "88888888-8888-8888-8888-888888888888";
        String sourceId = "legacy-online-source";
        jdbc.update("""
                INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,deleted,local,collection_root,format)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, bookId, "Legacy remote", "remote.fb2", "", "", 100, 0, 0, "/remote", "FB2");
        jdbc.update("INSERT INTO catalog_sources(source_id,source_key,source_fingerprint) VALUES (?,?,?)",
                sourceId, "legacy-online", "fingerprint");
        jdbc.update("""
                INSERT INTO catalog_book_state(
                    book_id,source_id,source_book_key,catalog_revision,catalog_fingerprint,
                    catalog_file_name,catalog_folder,catalog_archive_entry,catalog_file_size,
                    first_seen_revision,last_seen_revision)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, bookId, sourceId, "legacy-key", 1, "book-fingerprint",
                "remote.fb2", "", "", 100, 1, 1);

        repository.updateStorage(BookId.fromString(bookId), "/library", "downloads", "remote.fb2", "", true);

        String artifactId = "legacy:" + bookId;
        assertThat(jdbc.queryForObject("SELECT source_id FROM book_artifacts WHERE artifact_id=?", String.class, artifactId))
                .isEqualTo(sourceId);
        assertThat(jdbc.queryForObject("SELECT remote FROM book_artifacts WHERE artifact_id=?", Integer.class, artifactId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM book_artifacts WHERE artifact_id=?", String.class, artifactId))
                .isEqualTo("AVAILABLE");

        repository.markStorageMissing(BookId.fromString(bookId));
        assertThat(jdbc.queryForObject("SELECT remote FROM book_artifacts WHERE artifact_id=?", Integer.class, artifactId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM book_artifacts WHERE artifact_id=?", String.class, artifactId))
                .isEqualTo("REMOTE_ONLY");
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
        assertThat(jdbc.queryForObject("SELECT format FROM books WHERE id=?", String.class, bookId)).isEqualTo("EPUB");
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
