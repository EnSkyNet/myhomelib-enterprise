package com.myhomelibcorp.infrastructure.maintenance;

import com.myhomelibcorp.application.collection.MaintenanceIssueType;
import com.myhomelibcorp.application.collection.MaintenanceIssue;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class CollectionMaintenanceAdapterTest {
    @TempDir Path tempDir;

    @Test
    void analyzeDryRunAndApplyUseBackupAndNeverDeleteOrphanPhysicalFiles() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("books"));
        Path dbFile = tempDir.resolve("library.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = spy(new JdbcTemplate(dataSource));
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
            assertThat(report.issues()).allSatisfy(issue -> {
                assertThat(issue.messageKey()).startsWith("ui.collection.maintenance.issue.");
                assertThat(issue.technicalDetail()).doesNotContain("ui.collection.maintenance.issue.");
            });
            assertThat(report.issues()).anyMatch(i -> i.type() == MaintenanceIssueType.MISSING_FILE
                    && i.messageKey().equals("ui.collection.maintenance.issue.missing_file"));
            assertThat(report.issues()).anyMatch(i -> i.type() == MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE
                    && i.messageKey().startsWith("ui.collection.maintenance.issue.archive_"));
            assertThat(report.issues()).anyMatch(i -> i.type() == MaintenanceIssueType.ORPHANED_AUTHOR
                    && i.messageKey().equals("ui.collection.maintenance.issue.orphan_author"));
            assertThat(report.issues()).anyMatch(i -> i.type() == MaintenanceIssueType.ORPHANED_GENRE
                    && i.messageKey().equals("ui.collection.maintenance.issue.orphan_genre"));
            assertThat(report.issues()).anyMatch(i -> i.type() == MaintenanceIssueType.DUPLICATE_BOOK
                    && i.messageKey().equals("ui.collection.maintenance.issue.duplicate_book"));
            assertThat(report.issues()).anyMatch(i -> i.type() == MaintenanceIssueType.ORPHAN_FILE && !i.repairable());

            Set<String> repairable = report.issues().stream().filter(i -> i.repairable())
                    .map(i -> i.issueId()).collect(Collectors.toSet());
            var dry = adapter.apply("c1", repairable, true);
            assertThat(dry.dryRun()).isTrue();
            assertThat(dry.backupFile()).isNull();
            assertThat(Files.exists(root.resolve("orphan.fb2"))).isTrue();
            assertThat(jdbc.queryForObject("SELECT local FROM books WHERE id='missing'", Integer.class)).isEqualTo(1);
            verify(jdbc, never()).execute("PRAGMA optimize");

            var applied = adapter.apply("c1", repairable, false);
            assertThat(applied.backupFile()).isNotNull();
            assertThat(Files.isRegularFile(applied.backupFile())).isTrue();
            assertThat(jdbc.queryForObject("SELECT local FROM books WHERE id='missing'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT local FROM books WHERE id='bad-archive'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM authors WHERE id='orphan-author'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM genres WHERE code='orphan-genre'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM books WHERE lib_id='DUP'", Integer.class)).isEqualTo(1);
            assertThat(Files.exists(root.resolve("orphan.fb2"))).isTrue();
            verify(jdbc).execute("PRAGMA optimize");
            verify(jdbc, never()).execute("REINDEX");
            verify(jdbc, never()).execute("ANALYZE");
        } finally {
            System.clearProperty("myhomelib.dataDir");
        }
    }


    @Test
    void inaccessibleOrMissingRootNeverLooksLikeSuccessfulOrphanScan() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("books-to-disappear"));
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("missing-root.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        Collection collection = new Collection("lost-root", "Test", root,
                tempDir.resolve("missing-root.db").toString(), 0, null, null, null, null);
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentCollection()).thenReturn(collection);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        JdbcTemplate metadata = mock(JdbcTemplate.class);
        CollectionMaintenanceAdapter adapter = new CollectionMaintenanceAdapter(manager, metadata);

        Files.delete(root);
        assertThatThrownBy(() -> adapter.analyze("lost-root"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot safely scan collection root");
        assertThatThrownBy(() -> adapter.apply("lost-root", Set.of(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot safely scan collection root");
    }

    @Test
    void maintenanceRefusesWritesWhenDatabaseQuickCheckIsNotOk() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("quickcheck-books"));
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("quickcheck.db"));
        JdbcTemplate realJdbc = new JdbcTemplate(dataSource);
        createSchema(realJdbc);
        realJdbc.update("INSERT INTO authors(id,first_name,last_name) VALUES('unreferenced','None','None')");
        JdbcTemplate jdbc = spy(realJdbc);
        doReturn(java.util.List.of("database disk image is malformed"))
                .when(jdbc).query(eq("PRAGMA quick_check"), org.mockito.ArgumentMatchers.<RowMapper<String>>any());

        Collection collection = new Collection("quickcheck", "Test", root,
                tempDir.resolve("quickcheck.db").toString(), 0, null, null, null, null);
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentCollection()).thenReturn(collection);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        JdbcTemplate metadata = mock(JdbcTemplate.class);
        CollectionMaintenanceAdapter adapter = new CollectionMaintenanceAdapter(manager, metadata);

        assertThatThrownBy(() -> adapter.apply("quickcheck", Set.of(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity check failed");
        assertThat(realJdbc.queryForObject("SELECT COUNT(*) FROM authors WHERE id='unreferenced'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void orphanScanDoesNotDescendIntoSymlinkedDirectory() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("linked-books"));
        Path external = Files.createDirectories(tempDir.resolve("outside-library"));
        Files.writeString(external.resolve("not-ours.fb2"), "unrelated");
        try {
            Files.createSymbolicLink(root.resolve("linked"), external);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links not supported: " + unsupported.getMessage());
        }
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("linked.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        Collection collection = new Collection("linked", "Test", root,
                tempDir.resolve("linked.db").toString(), 0, null, null, null, null);
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentCollection()).thenReturn(collection);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        // The metadata source-watch table must exist for the orphan scan to finish.
        SQLiteDataSource metaDs = new SQLiteDataSource();
        metaDs.setUrl("jdbc:sqlite:" + tempDir.resolve("linked-meta.db"));
        JdbcTemplate actualMetadata = new JdbcTemplate(metaDs);
        actualMetadata.execute("CREATE TABLE collection_source_watch(collection_id TEXT PRIMARY KEY, source_file TEXT)");
        CollectionMaintenanceAdapter adapter = new CollectionMaintenanceAdapter(manager, actualMetadata);
        var report = adapter.analyze("linked");
        assertThat(report.scannedFiles()).isZero();
        assertThat(report.orphanFiles()).isZero();
    }

    @Test
    void maintenanceMustNotMarkSymlinkedFileAsMissingOrRepairable() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("linked-leaf-root"));
        Path outside = Files.writeString(tempDir.resolve("outside-book.fb2"), "external");
        try {
            Files.createSymbolicLink(root.resolve("book.fb2"), outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable");
        }
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("leaf.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createSchema(jdbc);
        insertBook(jdbc, "leaf", "Book", "book.fb2", "", "", "X", 1);
        Collection collection = new Collection("leaf", "Leaf", root,
                tempDir.resolve("leaf.db").toString(), 0, null, null, null, null);
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentCollection()).thenReturn(collection);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        CollectionMaintenanceAdapter adapter = new CollectionMaintenanceAdapter(manager, mock(JdbcTemplate.class));
        assertThatThrownBy(() -> adapter.analyze("leaf"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot safely verify book resource");
        assertThatThrownBy(() -> adapter.apply("leaf", Set.of(), false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT local FROM books WHERE id='leaf'", Integer.class)).isEqualTo(1);
    }

    @Test
    void analyzeRejectsCollectionSwitchBeforeReturningResults() throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("race.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createSchema(jdbc);
        Collection first = new Collection("race", "First", null,
                tempDir.resolve("race.db").toString(), 0, null, null, null, null);
        Collection other = new Collection("other", "Other", null,
                tempDir.resolve("race.db").toString(), 0, null, null, null, null);
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentCollection()).thenReturn(first, other);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        CollectionMaintenanceAdapter adapter = new CollectionMaintenanceAdapter(manager, mock(JdbcTemplate.class));
        assertThatThrownBy(() -> adapter.analyze("race"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed during maintenance analysis");
    }

    @Test
    void staleDuplicateMustNotBeDeletedAfterItsStorageIdentityChanges() throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("stale-duplicate.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createSchema(jdbc);
        insertBook(jdbc, "duplicate-a", "A", "a.fb2", "", "", "SAME", 0);
        insertBook(jdbc, "duplicate-b", "B", "a.fb2", "", "", "SAME", 0);
        jdbc.update("UPDATE books SET file_name='new-location.fb2' WHERE id='duplicate-b'");
        MaintenanceIssue stale = new MaintenanceIssue("DUPLICATE_BOOK:duplicate-b",
                MaintenanceIssueType.DUPLICATE_BOOK, "duplicate-b", "issue", java.util.List.of(), "", true, true);
        try (var conn = ds.getConnection()) {
            assertThat(CollectionMaintenanceAdapter.applyIssue(conn, stale)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM books", Integer.class)).isEqualTo(2);
    }

    @Test
    void unreadableArchiveIsReportedButNeverOfferedForAutomaticLocalReset() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("damaged-archive-root"));
        Files.write(root.resolve("damaged.zip"), new byte[]{1, 2, 3, 4, 5});
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("damaged.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createSchema(jdbc);
        insertBook(jdbc, "damaged-book", "Book", "one.fb2", "damaged.zip", "one.fb2", "ID", 1);
        Collection collection = new Collection("damaged", "Damaged", root,
                tempDir.resolve("damaged.db").toString(), 0, null, null, null, null);
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentCollection()).thenReturn(collection);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        // No metadata query needed when the collection has no orphan-scan root? Here it has one.
        SQLiteDataSource metaDs = new SQLiteDataSource();
        metaDs.setUrl("jdbc:sqlite:" + tempDir.resolve("damaged-meta.db"));
        JdbcTemplate metadata = new JdbcTemplate(metaDs);
        metadata.execute("CREATE TABLE collection_source_watch(collection_id TEXT PRIMARY KEY, source_file TEXT)");
        CollectionMaintenanceAdapter adapter = new CollectionMaintenanceAdapter(manager, metadata);
        var report = adapter.analyze("damaged");
        assertThat(report.invalidArchiveReferences()).isEqualTo(1);
        assertThat(report.issues()).anySatisfy(issue -> {
            if (issue.type() == MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE) {
                assertThat(issue.repairable()).isFalse();
            }
        });
        assertThat(report.issues().stream()
                .filter(i -> i.type() == MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE))
                .allMatch(i -> !i.repairable());
        assertThat(jdbc.queryForObject("SELECT local FROM books WHERE id='damaged-book'", Integer.class)).isEqualTo(1);
    }

    @Test
    void maintenanceRechecksMissingFileBeforeChangingItsLocalStatus() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("recheck-files"));
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("recheck-files.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createSchema(jdbc);
        insertBook(jdbc, "late", "Late Book", "late.fb2", "", "", "LATE", 1);
        Collection collection = new Collection("recheck", "Recheck", root,
                tempDir.resolve("recheck-files.db").toString(), 0, null, null, null, null);
        CollectionManager manager = mock(CollectionManager.class);
        CollectionMaintenanceAnalyzer analyzer = new CollectionMaintenanceAnalyzer(manager, mock(JdbcTemplate.class));
        MaintenanceIssue issue = new MaintenanceIssue("MISSING_FILE:late", MaintenanceIssueType.MISSING_FILE,
                "late", "issue", java.util.List.of(), "", true, false);
        try (var connection = ds.getConnection()) {
            assertThat(analyzer.isPhysicalIssueStillRepairable(collection, connection, issue)).isTrue();
            Files.writeString(root.resolve("late.fb2"), "restored");
            assertThat(analyzer.isPhysicalIssueStillRepairable(collection, connection, issue)).isFalse();
            jdbc.update("UPDATE books SET file_name='another.fb2' WHERE id='late'");
            // Recheck uses the current database row rather than a stale filename from the report.
            assertThat(analyzer.isPhysicalIssueStillRepairable(collection, connection, issue)).isTrue();
            jdbc.update("UPDATE books SET local=0 WHERE id='late'");
            assertThat(analyzer.isPhysicalIssueStillRepairable(collection, connection, issue)).isFalse();
        }
    }

    @Test
    void archiveEntryReappearingOrBecomingUnreadableMustNotClearLocalStatus() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("recheck-archive"));
        Path zip = root.resolve("archive.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("other.fb2"));
            out.write("other".getBytes());
            out.closeEntry();
        }
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("recheck-archive.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createSchema(jdbc);
        insertBook(jdbc, "inside", "Inside", "inside.fb2", "archive.zip", "inside.fb2", "ZIP", 1);
        Collection collection = new Collection("archive", "Archive", root,
                tempDir.resolve("recheck-archive.db").toString(), 0, null, null, null, null);
        CollectionMaintenanceAnalyzer analyzer = new CollectionMaintenanceAnalyzer(
                mock(CollectionManager.class), mock(JdbcTemplate.class));
        MaintenanceIssue issue = new MaintenanceIssue("INVALID_ARCHIVE_REFERENCE:inside",
                MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE, "inside", "issue",
                java.util.List.of(), "", true, false);
        try (var connection = ds.getConnection()) {
            assertThat(analyzer.isPhysicalIssueStillRepairable(collection, connection, issue)).isTrue();
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry("inside.fb2"));
                out.write("restored".getBytes());
                out.closeEntry();
            }
            assertThat(analyzer.isPhysicalIssueStillRepairable(collection, connection, issue)).isFalse();
            Files.write(zip, new byte[]{0x01, 0x02, 0x03});
            assertThat(analyzer.isPhysicalIssueStillRepairable(collection, connection, issue)).isFalse();
        }
    }

    @Test
    void restoredResourcesDuringMandatoryBackupAreNotMarkedMissingOnApply() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("late-restore-root"));
        Path zip = root.resolve("restore.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("other.fb2"));
            out.write("original".getBytes());
            out.closeEntry();
        }
        Path dbFile = tempDir.resolve("late-restore.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = spy(new JdbcTemplate(ds));
        createSchema(jdbc);
        insertBook(jdbc, "restored-file", "Restored", "restored.fb2", "", "", "F", 1);
        insertBook(jdbc, "restored-entry", "Inside", "inside.fb2", "restore.zip", "inside.fb2", "Z", 1);
        Collection collection = new Collection("late-restore", "Late restore", root,
                dbFile.toString(), 0, null, null, null, null);
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentCollection()).thenReturn(collection);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        when(manager.getCurrentDataSource()).thenReturn(ds);
        SQLiteDataSource metaDs = new SQLiteDataSource();
        metaDs.setUrl("jdbc:sqlite:" + tempDir.resolve("late-restore-meta.db"));
        JdbcTemplate metadata = new JdbcTemplate(metaDs);
        metadata.execute("CREATE TABLE collection_source_watch(collection_id TEXT PRIMARY KEY, source_file TEXT)");
        // The backup is created after the UI diagnosis but before applying the SQL.
        org.mockito.Mockito.doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object result = invocation.callRealMethod();
            if (sql.startsWith("VACUUM INTO")) {
                Files.writeString(root.resolve("restored.fb2"), "returned");
                try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                    out.putNextEntry(new ZipEntry("inside.fb2"));
                    out.write("returned".getBytes());
                    out.closeEntry();
                }
            }
            return result;
        }).when(jdbc).execute(org.mockito.ArgumentMatchers.anyString());
        System.setProperty("myhomelib.dataDir", tempDir.resolve("late-restore-appdata").toString());
        try {
            var result = new CollectionMaintenanceAdapter(manager, metadata)
                    .apply("late-restore", Set.of(), false);
            assertThat(result.applied()).isZero();
            assertThat(result.skipped()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT local FROM books WHERE id='restored-file'", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT local FROM books WHERE id='restored-entry'", Integer.class))
                    .isEqualTo(1);
            assertThat(Files.exists(root.resolve("restored.fb2"))).isTrue();
            assertThat(Files.size(result.backupFile())).isPositive();
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
