package com.myhomelibcorp.infrastructure.collection;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.config.DataSourceConfig;
import com.myhomelibcorp.infrastructure.cover.ZipArchiveReader;
import com.myhomelibcorp.infrastructure.resource.BookResourceResolver;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.util.CatalogUpdateRecoveryFiles;
import com.myhomelibcorp.shared.util.RestoreRecoveryFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CollectionManagerCrashRecoveryTest {

    @TempDir
    Path temp;

    private String previousDataDir;
    private CollectionManager manager;

    @BeforeEach
    void configureDataDir() {
        previousDataDir = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.resolve("app-data").toString());
    }

    @AfterEach
    void cleanup() {
        if (manager != null) manager.closeCurrentCollection();
        if (previousDataDir == null) System.clearProperty("myhomelib.dataDir");
        else System.setProperty("myhomelib.dataDir", previousDataDir);
    }

    @Test
    void restoresPreUpdateCheckpointBeforeOpeningCollectionAfterCrash() throws Exception {
        Path target = temp.resolve("library.db");
        createDatabase(target, "partial-update");
        Path checkpoint = CatalogUpdateRecoveryFiles.checkpoint("collection-crash");
        Files.createDirectories(checkpoint.getParent());
        createDatabase(checkpoint, "baseline-before-update");
        CatalogUpdateRecoveryFiles.markPending("collection-crash", "catalog-update-test");

        manager = newManager();
        manager.switchToCollection(collection("collection-crash", target));

        assertThat(readState(manager.getCurrentJdbcTemplate())).isEqualTo("baseline-before-update");
        assertThat(CatalogUpdateRecoveryFiles.isPending("collection-crash")).isFalse();
        assertThat(Files.exists(checkpoint)).isFalse();
        assertThat(Files.exists(Path.of(target + ".catalog-update.crashed-current"))).isFalse();
    }

    @Test
    void rejectsIndexCorruptCheckpointAlthoughSqliteQuickCheckReportsOk() throws Exception {
        Path target = temp.resolve("original-catalog.db");
        createDatabase(target, "current-safe-catalog");
        Path checkpoint = CatalogUpdateRecoveryFiles.checkpoint("corrupt-checkpoint");
        Files.createDirectories(checkpoint.getParent());
        createDatabase(checkpoint, "baseline-before-update");

        // Synthetic corruption of one B-tree index payload byte: the page remains
        // structurally valid and PRAGMA quick_check says "ok", but the index no
        // longer matches its source table. This fixture never touches user data.
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + checkpoint.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("CREATE INDEX idx_recovery_value ON recovery_state(value)");
        }
        int indexRootPage;
        int pageSize;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + checkpoint.toAbsolutePath());
             var statement = connection.createStatement()) {
            try (var index = statement.executeQuery(
                    "SELECT rootpage FROM sqlite_master WHERE name='idx_recovery_value'")) {
                assertThat(index.next()).isTrue();
                indexRootPage = index.getInt(1);
            }
            try (var size = statement.executeQuery("PRAGMA page_size")) {
                assertThat(size.next()).isTrue();
                pageSize = size.getInt(1);
            }
        }
        try (var bytes = new java.io.RandomAccessFile(checkpoint.toFile(), "rw")) {
            long pageStart = (indexRootPage - 1L) * pageSize;
            bytes.seek(pageStart);
            assertThat(bytes.readUnsignedByte()).isEqualTo(0x0a); // leaf index B-tree
            bytes.seek(pageStart + 8);
            int cellOffset = bytes.readUnsignedShort();
            // Index record layout for one short TEXT key with rowid:
            // varint payload len, header size, text serial type, rowid serial type, TEXT.
            bytes.seek(pageStart + cellOffset + 4);
            assertThat(bytes.readUnsignedByte()).isEqualTo((int) 'b');
            bytes.seek(pageStart + cellOffset + 4);
            bytes.writeByte('Z');
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + checkpoint.toAbsolutePath());
             var statement = connection.createStatement()) {
            try (var quick = statement.executeQuery("PRAGMA quick_check")) {
                assertThat(quick.next()).isTrue();
                assertThat(quick.getString(1)).isEqualTo("ok");
            }
            try (var full = statement.executeQuery("PRAGMA integrity_check")) {
                assertThat(full.next()).isTrue();
                assertThat(full.getString(1)).contains("missing from index");
            }
        }
        CatalogUpdateRecoveryFiles.markPending("corrupt-checkpoint", "catalog-update-test");
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> CollectionCrashRecovery.recoverBeforeOpen(
                        collection("corrupt-checkpoint", target), target));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> new com.myhomelibcorp.infrastructure.adapter.CollectionBackupAdapter(
                        mock(CollectionManager.class)).validateDatabaseFile(checkpoint));
        assertThat(readState(target)).isEqualTo("current-safe-catalog");
        assertThat(Files.exists(checkpoint)).isTrue();
        assertThat(CatalogUpdateRecoveryFiles.isPending("corrupt-checkpoint")).isTrue();
    }

    @Test
    void rollsBackInterruptedUserRestoreWhenPreviousDatabaseStillExists() throws Exception {
        Path target = temp.resolve("restore.db");
        createDatabase(target, "candidate-not-committed");
        Path previous = Path.of(target + ".restore.previous");
        Path staged = Path.of(target + ".restore.tmp");
        createDatabase(previous, "old-committed-database");
        createDatabase(staged, "staged-leftover");
        RestoreRecoveryFiles.markPending(target);

        manager = newManager();
        manager.switchToCollection(collection("restore-crash", target));

        assertThat(readState(manager.getCurrentJdbcTemplate())).isEqualTo("old-committed-database");
        assertThat(Files.exists(previous)).isFalse();
        assertThat(Files.exists(staged)).isFalse();
        assertThat(RestoreRecoveryFiles.isPending(target)).isFalse();
    }

    @Test
    void keepsCommittedRestoreWhenOnlyStalePreviousDatabaseRemains() throws Exception {
        Path target = temp.resolve("restore-committed.db");
        createDatabase(target, "new-committed-database");
        Path previous = RestoreRecoveryFiles.previous(target);
        createDatabase(previous, "old-database-cleanup-residue");

        manager = newManager();
        manager.switchToCollection(collection("restore-committed", target));

        assertThat(readState(manager.getCurrentJdbcTemplate())).isEqualTo("new-committed-database");
        assertThat(Files.exists(previous)).isFalse();
    }


    @Test
    void refusesSymlinkedRestorePendingMarkerWithoutTouchingLiveDatabase() throws Exception {
        Path target = temp.resolve("restore-symlink-marker.db");
        createDatabase(target, "live-safe");
        Path foreign = temp.resolve("foreign-marker.txt");
        Files.writeString(foreign, "FOREIGN");
        Path marker = RestoreRecoveryFiles.pending(target);
        try {
            Files.createSymbolicLink(marker, foreign);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are not supported");
        }

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> CollectionCrashRecovery.recoverBeforeOpen(collection("restore-symlink", target), target));

        assertThat(readState(target)).isEqualTo("live-safe");
        assertThat(Files.readString(foreign)).isEqualTo("FOREIGN");
        assertThat(Files.isSymbolicLink(marker)).isTrue();
    }

    @Test
    void refusesSymlinkedCatalogRecoveryMarkerWithoutTouchingLiveDatabase() throws Exception {
        Path target = temp.resolve("catalog-symlink-marker.db");
        createDatabase(target, "live-safe");
        Path marker = CatalogUpdateRecoveryFiles.marker("catalog-symlink");
        Files.createDirectories(marker.getParent());
        Path foreign = temp.resolve("foreign-catalog-marker.txt");
        Files.writeString(foreign, "FOREIGN");
        try {
            Files.createSymbolicLink(marker, foreign);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are not supported");
        }

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> CollectionCrashRecovery.recoverBeforeOpen(collection("catalog-symlink", target), target));

        assertThat(readState(target)).isEqualTo("live-safe");
        assertThat(Files.readString(foreign)).isEqualTo("FOREIGN");
        assertThat(Files.isSymbolicLink(marker)).isTrue();
    }


    @Test
    void refusesDatabaseUnderSymlinkedParentBeforeOpeningIt() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside-db-dir"));
        Path linkedParent = temp.resolve("linked-db-dir");
        try {
            Files.createSymbolicLink(linkedParent, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are not supported");
        }

        Path targetThroughLink = linkedParent.resolve("library.db");
        manager = newManager();

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> manager.switchToCollection(collection("symlink-parent", targetThroughLink)));

        assertThat(outside.resolve("library.db")).doesNotExist();
        assertThat(manager.hasActiveCollection()).isFalse();
    }

    @Test
    void refusesSymlinkDatabaseLeafWithoutOpeningForeignDatabase() throws Exception {
        Path foreign = temp.resolve("foreign.db");
        createDatabase(foreign, "foreign");
        Path link = temp.resolve("linked.db");
        try {
            Files.createSymbolicLink(link, foreign);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are not supported");
        }

        manager = newManager();
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> manager.switchToCollection(collection("symlink-leaf", link)));

        assertThat(readState(foreign)).isEqualTo("foreign");
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(manager.hasActiveCollection()).isFalse();
    }

    @Test
    void reconcilesInterruptedLocalCopyDeletionBeforeCollectionDatabaseIsOpened() throws Exception {
        BookId bookId = BookId.generate();
        Path target = temp.resolve("local-copy-crash.db");
        createDatabaseWithBook(target, "catalog-still-local", bookId, true);

        Path root = Files.createDirectories(temp.resolve("managed-library"));
        Path book = root.resolve("book.fb2");
        Files.writeString(book, "durable book bytes");
        BookResourceResolver resolver = new BookResourceResolver(mock(ZipArchiveReader.class));
        var staged = resolver.stagePhysicalFileForDeletion(book, root, "local-copy-crash", List.of(bookId));
        assertThat(book).doesNotExist();
        assertThat(staged.recoveryPath()).exists();

        // Simulate process death by dropping the in-memory staged handle without commit/rollback.
        manager = newManager();
        manager.switchToCollection(collection("local-copy-crash", target));

        assertThat(readState(manager.getCurrentJdbcTemplate())).isEqualTo("catalog-still-local");
        assertThat(book).exists();
        assertThat(Files.readString(book)).isEqualTo("durable book bytes");
        assertThat(staged.recoveryPath()).doesNotExist();
    }

    @Test
    void completesFirstTimeRestoreFromValidatedStagedDatabaseWhenTargetIsMissing() throws Exception {
        Path target = temp.resolve("first-restore.db");
        Path staged = Path.of(target + ".restore.tmp");
        createDatabase(staged, "only-recoverable-candidate");

        manager = newManager();
        manager.switchToCollection(collection("restore-first", target));

        assertThat(readState(manager.getCurrentJdbcTemplate())).isEqualTo("only-recoverable-candidate");
        assertThat(Files.exists(staged)).isFalse();
    }

    private CollectionManager newManager() {
        JdbcTemplate metadata = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite::memory:"));
        return new CollectionManager(metadata, new DataSourceConfig());
    }

    private static Collection collection(String id, Path db) {
        return new Collection(id, id, null, db.toAbsolutePath().toString(), 0,
                null, null, null, null);
    }

    private static void createDatabase(Path file, String state) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.deleteIfExists(file);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE recovery_state(value TEXT NOT NULL)");
            try (var prepared = connection.prepareStatement("INSERT INTO recovery_state(value) VALUES (?)")) {
                prepared.setString(1, state);
                prepared.executeUpdate();
            }
        }
    }


    private static void createDatabaseWithBook(Path file, String state, BookId bookId, boolean local) throws Exception {
        createDatabase(file, state);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE books (id TEXT PRIMARY KEY, local INTEGER NOT NULL)");
            try (var prepared = connection.prepareStatement("INSERT INTO books(id, local) VALUES (?, ?)")) {
                prepared.setString(1, bookId.asString());
                prepared.setInt(2, local ? 1 : 0);
                prepared.executeUpdate();
            }
        }
    }


    private static String readState(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT value FROM recovery_state")) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static String readState(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT value FROM recovery_state", String.class);
    }
}
