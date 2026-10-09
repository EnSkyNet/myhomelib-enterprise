package com.myhomelibcorp.infrastructure.persistence.sqlite;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression: a generated conversion must never replace the original catalogue projection. */
class ConversionProjectionRepairV63Test {

    @TempDir
    Path tempDir;

    @Test
    void migrationRestoresOriginalArtifactAndBookProjectionFromConversionMetadata() {
        Path db = tempDir.resolve("conversion-projection.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("62"))
                .load()
                .migrate();

        jdbc.update("""
                INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,collection_root,format,local,deleted)
                VALUES ('book-conv','Conversion regression','converted.epub','converted','','456','/converted','epub',1,0)
                """);
        jdbc.update("""
                INSERT INTO book_artifacts(
                    artifact_id,book_id,source_id,artifact_name,media_type,file_format,file_name,archive_name,
                    archive_entry,size_bytes,remote,local,collection_root,folder,state)
                VALUES ('original-fb2','book-conv','inpx:flibusta','original.zip','application/zip','fb2_zip',
                        'original.zip','original.zip','original.fb2',123,0,1,'/library','books','AVAILABLE')
                """);
        jdbc.update("""
                INSERT INTO book_artifacts(
                    artifact_id,book_id,source_id,artifact_name,media_type,file_format,file_name,archive_name,
                    archive_entry,size_bytes,remote,local,collection_root,folder,state)
                VALUES ('converted-epub','book-conv','conversion:test','converted.epub','application/epub+zip','epub',
                        'converted.epub',NULL,'',456,0,1,'/converted','converted','AVAILABLE')
                """);
        jdbc.update("INSERT INTO book_artifact_metadata(artifact_id,metadata_key,metadata_value) VALUES ('converted-epub','conversion.sourceArtifactId','original-fb2')");
        jdbc.update("INSERT INTO book_artifact_preferences(book_id,preferred_artifact_id) VALUES ('book-conv','converted-epub')");

        Flyway flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("70");
        assertThat(jdbc.queryForObject("SELECT preferred_artifact_id FROM book_artifact_preferences WHERE book_id='book-conv'", String.class))
                .isEqualTo("original-fb2");
        assertThat(jdbc.queryForObject("SELECT file_name FROM books WHERE id='book-conv'", String.class)).isEqualTo("original.zip");
        assertThat(jdbc.queryForObject("SELECT folder FROM books WHERE id='book-conv'", String.class)).isEqualTo("books");
        assertThat(jdbc.queryForObject("SELECT archive_entry FROM books WHERE id='book-conv'", String.class)).isEqualTo("original.fb2");
        assertThat(jdbc.queryForObject("SELECT collection_root FROM books WHERE id='book-conv'", String.class)).isEqualTo("/library");
        assertThat(jdbc.queryForObject("SELECT format FROM books WHERE id='book-conv'", String.class)).isEqualTo("ZIP");
        assertThat(jdbc.queryForObject("SELECT file_size FROM books WHERE id='book-conv'", Long.class)).isEqualTo(123L);
        assertThat(jdbc.queryForObject("SELECT local FROM books WHERE id='book-conv'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_artifacts WHERE book_id='book-conv'", Integer.class)).isEqualTo(2);
    }
}
