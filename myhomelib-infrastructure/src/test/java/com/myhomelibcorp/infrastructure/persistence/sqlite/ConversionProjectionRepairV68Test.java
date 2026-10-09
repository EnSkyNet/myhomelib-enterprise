package com.myhomelibcorp.infrastructure.persistence.sqlite;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression for post-V50 books that reached conversion without an explicit artifact preference. */
class ConversionProjectionRepairV68Test {

    @TempDir
    Path tempDir;

    @Test
    void missingPreferenceAndConvertedProjectionAreRepairedBackToRecordedSource() {
        Path db = tempDir.resolve("missing-preference-conversion.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        migrateTo67(ds);
        insertBookAndArtifacts(jdbc, true);

        Flyway flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("70");
        assertThat(jdbc.queryForObject(
                "SELECT preferred_artifact_id FROM book_artifact_preferences WHERE book_id='book-no-pref'",
                String.class)).isEqualTo("z-original");
        assertOriginalProjection(jdbc);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM book_artifacts WHERE book_id='book-no-pref'", Integer.class)).isEqualTo(2);
    }

    @Test
    void missingPreferenceWithOriginalProjectionGetsExplicitOriginalPreferenceWithoutRewritingBook() {
        Path db = tempDir.resolve("missing-preference-original.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        migrateTo67(ds);
        insertBookAndArtifacts(jdbc, false);

        Flyway flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        flyway.migrate();

        assertThat(jdbc.queryForObject(
                "SELECT preferred_artifact_id FROM book_artifact_preferences WHERE book_id='book-no-pref'",
                String.class)).isEqualTo("z-original");
        assertOriginalProjection(jdbc);
    }

    private static void migrateTo67(DriverManagerDataSource ds) {
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("67"))
                .load()
                .migrate();
    }

    private static void insertBookAndArtifacts(JdbcTemplate jdbc, boolean convertedProjection) {
        String fileName = convertedProjection ? "converted.epub" : "original.zip";
        String folder = convertedProjection ? "converted" : "books";
        String archiveEntry = convertedProjection ? "" : "original.fb2";
        long fileSize = convertedProjection ? 456L : 123L;
        String root = convertedProjection ? "/converted" : "/library";
        String format = convertedProjection ? "epub" : "fb2_zip";

        jdbc.update("""
                INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,collection_root,format,local,deleted)
                VALUES ('book-no-pref','Missing preference',?,?,?,?,?,?,1,0)
                """, fileName, folder, archiveEntry, fileSize, root, format);
        jdbc.update("""
                INSERT INTO book_artifacts(
                    artifact_id,book_id,source_id,artifact_name,media_type,file_format,file_name,archive_name,
                    archive_entry,size_bytes,remote,local,collection_root,folder,state)
                VALUES ('z-original','book-no-pref','inpx:flibusta','original.zip','application/zip','fb2_zip',
                        'original.zip','original.zip','original.fb2',123,0,1,'/library','books','AVAILABLE')
                """);
        jdbc.update("""
                INSERT INTO book_artifacts(
                    artifact_id,book_id,source_id,artifact_name,media_type,file_format,file_name,archive_name,
                    archive_entry,size_bytes,remote,local,collection_root,folder,state)
                VALUES ('a-conversion','book-no-pref','conversion:test','converted.epub','application/epub+zip','epub',
                        'converted.epub',NULL,'',456,0,1,'/converted','converted','AVAILABLE')
                """);
        jdbc.update("""
                INSERT INTO book_artifact_metadata(artifact_id,metadata_key,metadata_value)
                VALUES ('a-conversion','conversion.sourceArtifactId','z-original')
                """);
    }

    private static void assertOriginalProjection(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT file_name FROM books WHERE id='book-no-pref'", String.class))
                .isEqualTo("original.zip");
        assertThat(jdbc.queryForObject("SELECT folder FROM books WHERE id='book-no-pref'", String.class))
                .isEqualTo("books");
        assertThat(jdbc.queryForObject("SELECT archive_entry FROM books WHERE id='book-no-pref'", String.class))
                .isEqualTo("original.fb2");
        assertThat(jdbc.queryForObject("SELECT collection_root FROM books WHERE id='book-no-pref'", String.class))
                .isEqualTo("/library");
        assertThat(jdbc.queryForObject("SELECT format FROM books WHERE id='book-no-pref'", String.class))
                .isEqualTo("ZIP");
        assertThat(jdbc.queryForObject("SELECT file_size FROM books WHERE id='book-no-pref'", Long.class))
                .isEqualTo(123L);
    }
}
