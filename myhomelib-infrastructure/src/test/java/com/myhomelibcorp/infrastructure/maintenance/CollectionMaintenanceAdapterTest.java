package com.myhomelibcorp.infrastructure.maintenance;

import com.myhomelibcorp.application.collection.MaintenanceIssueType;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionMaintenanceAdapterTest {
    @TempDir Path tempDir;

    @Test
    void analyzeDryRunAndApplyUseBackupAndNeverDeleteOrphanPhysicalFiles() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("books"));
        Path dbFile = tempDir.resolve("library.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);

        Files.writeString(root.resolve("ok.fb2"), "<FictionBook/>");
        Files.writeString(root.resolve("orphan.fb2"), "orphan");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(root.resolve("books.zip")))) {
            out.putNextEntry(new ZipEntry("one.fb2"));
            out.write("one".getBytes());
            out.closeEntry();
        }

        insertBook(jdbc, "ok", "OK", "ok.fb2", "", "", "L1", 1);
        insertBook(jdbc, "missing", "Missing", "missing.fb2", "", "", "L2", 1);
        insertBook(jdbc, "bad-archive", "Bad archive", "ignored.fb2", "books.zip", "missing.fb2", "L3", 1);
        insertBook(jdbc, "dup-a", "Dup", "ok.fb2", "", "", "DUP", 0);
        insertBook(jdbc, "dup-b", "Dup", "ok.fb2", "", "", "DUP", 0);
        jdbc.update("INSERT INTO authors(id,first_name,last_name) VALUES('orphan-author','No','Books')");
        jdbc.update("INSERT INTO genres(code,name) VALUES('orphan-genre','No books')");

        Collection collection = new Collection("c1", "Test", root, dbFile.toString(), 0, null, null, null, null);
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentCollection()).thenReturn(collection);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        when(manager.getCurrentDataSource()).thenReturn(dataSource);

        SQLiteDataSource metaDs = new SQLiteDataSource();
        metaDs.setUrl("jdbc:sqlite:" + tempDir.resolve("meta.db"));
        JdbcTemplate meta = new JdbcTemplate(metaDs);
        meta.execute("CREATE TABLE collection_source_watch(collection_id TEXT PRIMARY KEY, source_file TEXT)");

        System.setProperty("myhomelib.dataDir", tempDir.resolve("appdata").toString());
        try {
            CollectionMaintenanceAdapter adapter = new CollectionMaintenanceAdapter(manager, meta);
            var report = adapter.analyze("c1");
            assertThat(report.databaseIntegrityOk()).isTrue();
            assertThat(report.missingFiles()).isEqualTo(1);
            assertThat(report.invalidArchiveReferences()).isEqualTo(1);
            assertThat(report.orphanFiles()).isEqualTo(1);
            assertThat(report.orphanedAuthors()).isEqualTo(1);
            assertThat(report.orphanedGenres()).isEqualTo(1);
            assertThat(report.duplicateBooks()).isEqualTo(1);
            assertThat(report.issues()).anyMatch(i -> i.type() == MaintenanceIssueType.ORPHAN_FILE && !i.repairable());

            Set<String> repairable = report.issues().stream().filter(i -> i.repairable())
                    .map(i -> i.issueId()).collect(Collectors.toSet());
            var dry = adapter.apply("c1", repairable, true);
            assertThat(dry.dryRun()).isTrue();
            assertThat(dry.backupFile()).isNull();
            assertThat(Files.exists(root.resolve("orphan.fb2"))).isTrue();
            assertThat(jdbc.queryForObject("SELECT local FROM books WHERE id='missing'", Integer.class)).isEqualTo(1);

            var applied = adapter.apply("c1", repairable, false);
            assertThat(applied.backupFile()).isNotNull();
            assertThat(Files.isRegularFile(applied.backupFile())).isTrue();
            assertThat(jdbc.queryForObject("SELECT local FROM books WHERE id='missing'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT local FROM books WHERE id='bad-archive'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM authors WHERE id='orphan-author'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM genres WHERE code='orphan-genre'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM books WHERE lib_id='DUP'", Integer.class)).isEqualTo(1);
            assertThat(Files.exists(root.resolve("orphan.fb2"))).isTrue();
        } finally {
            System.clearProperty("myhomelib.dataDir");
        }
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("PRAGMA foreign_keys=ON");
        jdbc.execute("""
                CREATE TABLE books(
                    id TEXT PRIMARY KEY, title TEXT NOT NULL, file_name TEXT NOT NULL, folder TEXT,
                    archive_entry TEXT, collection_root TEXT, lib_id TEXT, local INTEGER DEFAULT 0,
                    deleted INTEGER DEFAULT 0)
                """);
        jdbc.execute("CREATE TABLE authors(id TEXT PRIMARY KEY, first_name TEXT, middle_name TEXT, last_name TEXT)");
        jdbc.execute("CREATE TABLE genres(code TEXT PRIMARY KEY, name TEXT)");
        jdbc.execute("CREATE TABLE book_authors(book_id TEXT, author_id TEXT, FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE, FOREIGN KEY(author_id) REFERENCES authors(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE book_genres(book_id TEXT, genre_code TEXT, FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE, FOREIGN KEY(genre_code) REFERENCES genres(code) ON DELETE CASCADE)");
    }

    private static void insertBook(JdbcTemplate jdbc, String id, String title, String file, String folder,
                                   String archiveEntry, String libId, int local) {
        jdbc.update("INSERT INTO books(id,title,file_name,folder,archive_entry,collection_root,lib_id,local,deleted) VALUES(?,?,?,?,?,?,?,?,0)",
                id, title, file, folder, archiveEntry, "", libId, local);
    }
}
