package com.myhomelibcorp.infrastructure.persistence.sqlite;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteExactDuplicateScanStoreTest {
    @TempDir Path tempDir;

    @Test
    void scanQueueIsSnapshotBasedAndResumesPendingArtifactsAfterPause() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("exact-duplicates.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentDataSource(ds);
        manager.setCurrentJdbcTemplate(jdbc);
        SqliteExactDuplicateScanStore store = new SqliteExactDuplicateScanStore(manager);

        seedBook(jdbc, "11111111-1111-1111-1111-111111111111", "Book A", "a", "a.fb2");
        seedBook(jdbc, "22222222-2222-2222-2222-222222222222", "Book B", "b", "b.epub");

        var first = store.resumeOrStart();
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.resumed()).isFalse();
        var firstBatch = store.nextBatch(first.scanId(), 1);
        assertThat(firstBatch).hasSize(1);
        store.markHashed(first.scanId(), firstBatch.getFirst().artifactId(), "abc123", 17);
        store.pause(first.scanId());

        // Added after the scan started: must not enter the existing snapshot on resume.
        seedBook(jdbc, "33333333-3333-3333-3333-333333333333", "Book C", "c", "c.pdf");

        var resumed = store.resumeOrStart();
        assertThat(resumed.scanId()).isEqualTo(first.scanId());
        assertThat(resumed.resumed()).isTrue();
        assertThat(resumed.processed()).isEqualTo(1);
        assertThat(store.nextBatch(resumed.scanId(), 10))
                .extracting(candidate -> candidate.artifactId())
                .containsExactly("b");
        store.markHashed(resumed.scanId(), "b", "abc123", 17);
        var completed = store.complete(resumed.scanId());
        assertThat(completed.processed()).isEqualTo(2);
        assertThat(completed.hashed()).isEqualTo(2);

        var groups = store.findDuplicateGroups();
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().sha256()).isEqualTo("abc123");
        assertThat(groups.getFirst().artifacts()).extracting(a -> a.artifactId()).containsExactly("a", "b");

        // A completed scan creates a fresh snapshot, which now includes the new artifact.
        var next = store.resumeOrStart();
        assertThat(next.scanId()).isNotEqualTo(first.scanId());
        assertThat(next.total()).isEqualTo(3);
    }

    @Test
    void deletedArtifactFromSnapshotIsSkippedSoResumeCanComplete() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("exact-duplicates-deleted.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentDataSource(ds);
        manager.setCurrentJdbcTemplate(jdbc);
        SqliteExactDuplicateScanStore store = new SqliteExactDuplicateScanStore(manager);

        seedBook(jdbc, "11111111-1111-1111-1111-111111111111", "Book A", "a", "a.fb2");
        seedBook(jdbc, "22222222-2222-2222-2222-222222222222", "Book B", "b", "b.epub");

        var scan = store.resumeOrStart();
        var first = store.nextBatch(scan.scanId(), 1).getFirst();
        store.markHashed(scan.scanId(), first.artifactId(), "hash-a", 17);

        String deletedArtifact = first.artifactId().equals("a") ? "b" : "a";
        jdbc.update("DELETE FROM book_artifacts WHERE artifact_id=?", deletedArtifact);

        assertThat(store.nextBatch(scan.scanId(), 10)).isEmpty();
        var completed = store.complete(scan.scanId());
        assertThat(completed.processed()).isEqualTo(2);
        assertThat(completed.hashed()).isEqualTo(1);
        assertThat(completed.skipped()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT skip_reason FROM artifact_duplicate_scan_queue
                 WHERE scan_id=? AND artifact_id=?
                """, String.class, scan.scanId(), deletedArtifact))
                .isEqualTo("artifact removed during scan");
    }

    private static void seedBook(JdbcTemplate jdbc, String bookId, String title, String artifactId, String fileName) {
        jdbc.update("""
                INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,deleted,local,collection_root,format)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, bookId, title, fileName, "", "", 17, 0, 1, "/library", extension(fileName));
        jdbc.update("""
                INSERT INTO book_artifacts(artifact_id,book_id,artifact_name,file_format,file_name,size_bytes,
                                           remote,local,collection_root,folder,state)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, artifactId, bookId, fileName, extension(fileName), fileName, 17, 0, 1, "/library", "", "AVAILABLE");
    }

    private static String extension(String fileName) {
        return fileName.substring(fileName.lastIndexOf('.') + 1);
    }
}
