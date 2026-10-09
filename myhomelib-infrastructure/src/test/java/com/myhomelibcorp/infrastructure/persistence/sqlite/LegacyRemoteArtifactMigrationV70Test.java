package com.myhomelibcorp.infrastructure.persistence.sqlite;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression for V70: pre-artifact online catalog books must remain remote/downloadable. */
class LegacyRemoteArtifactMigrationV70Test {

    @TempDir
    Path tempDir;

    @Test
    void repairsLegacyArtifactRemoteStateFromCatalogBookState() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("v69-remote.db").toAbsolutePath());
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("69"))
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        String bookId = "70707070-7070-7070-7070-707070707070";
        String sourceId = "pre-v36-online";
        jdbc.update("""
                INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,deleted,local,collection_root,format)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, bookId, "Old online book", "old.fb2", "", "", 321, 0, 0, "/catalog", "FB2");
        jdbc.update("INSERT INTO catalog_sources(source_id,source_key,source_fingerprint) VALUES (?,?,?)",
                sourceId, "old-online-source", "source-fingerprint");
        jdbc.update("""
                INSERT INTO catalog_book_state(
                    book_id,source_id,source_book_key,catalog_revision,catalog_fingerprint,
                    catalog_file_name,catalog_folder,catalog_archive_entry,catalog_file_size,
                    first_seen_revision,last_seen_revision)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, bookId, sourceId, "old-key", 1, "book-fingerprint",
                "old.fb2", "", "", 321, 1, 1);
        jdbc.update("""
                INSERT INTO book_artifacts(
                    artifact_id,book_id,source_id,artifact_name,file_format,file_name,size_bytes,
                    remote,local,state)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, "legacy:" + bookId, bookId, null, "old.fb2", "FB2", "old.fb2", 321, 0, 0, "MISSING");

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("70");
        assertThat(jdbc.queryForObject(
                "SELECT source_id FROM book_artifacts WHERE artifact_id=?", String.class, "legacy:" + bookId))
                .isEqualTo(sourceId);
        assertThat(jdbc.queryForObject(
                "SELECT remote FROM book_artifacts WHERE artifact_id=?", Integer.class, "legacy:" + bookId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM book_artifacts WHERE artifact_id=?", String.class, "legacy:" + bookId))
                .isEqualTo("REMOTE_ONLY");
    }
}
