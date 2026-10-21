package com.myhomelibcorp.infrastructure.resource;

import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.cover.ZipArchiveReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LocalCopyDeletionRecoveryStoreTest {
    @TempDir Path temp;

    private String previousDataDir;
    private BookResourceResolver resolver;

    @BeforeEach
    void setUp() {
        previousDataDir = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.resolve("app-data").toString());
        resolver = new BookResourceResolver(mock(ZipArchiveReader.class));
    }

    @AfterEach
    void tearDown() {
        if (previousDataDir == null) System.clearProperty("myhomelib.dataDir");
        else System.setProperty("myhomelib.dataDir", previousDataDir);
    }

    @Test
    void largeRecoveryMarkerIdSetIsUniqueAndPreservesFirstOccurrenceOrder() {
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < 4_000; i++) {
            ids.add("book-" + i);
            if (i % 2 == 0) ids.add("book-" + i);
        }
        ids.add(" ");
        ids.add("book-10");
        var parsed = LocalCopyDeletionRecoveryStore.parseAffectedBookIds(String.join(",", ids));
        assertThat(parsed).hasSize(4_000);
        assertThat(parsed.getFirst()).isEqualTo("book-0");
        assertThat(parsed.get(10)).isEqualTo("book-10");
        assertThat(parsed.getLast()).isEqualTo("book-3999");
        assertThat(parsed).doesNotHaveDuplicates();
    }

    @Test
    void preparingLargeRecoveryMarkerPreservesIdOrderWithoutDuplicates() throws Exception {
        var ids = new java.util.ArrayList<BookId>();
        for (int i = 0; i < 2_000; i++) {
            BookId id = BookId.generate();
            ids.add(id);
            ids.add(id);
        }
        Path root = Files.createDirectories(temp.resolve("recovery-large-marker"));
        var marker = LocalCopyDeletionRecoveryStore.prepare(
                root.resolve("book.fb2"), root.resolve("book.pending"), root,
                "collection-test", ids, "0".repeat(64));
        try {
            Properties properties = new Properties();
            try (var input = Files.newInputStream(marker.path())) {
                properties.load(input);
            }
            var parsed = LocalCopyDeletionRecoveryStore.parseAffectedBookIds(
                    properties.getProperty("bookIds"));
            assertThat(parsed).hasSize(2_000);
            assertThat(parsed.getFirst()).isEqualTo(ids.getFirst().asString());
            assertThat(parsed.getLast()).isEqualTo(ids.get(ids.size() - 1).asString());
        } finally {
            Files.deleteIfExists(marker.path());
        }
    }

    @Test
    void oversizedMarkerCannotBePublishedBeyondTheCrashRecoveryReadLimit() throws Exception {
        Path root = Files.createDirectories(temp.resolve("recovery-oversized-marker"));
        var ids = new java.util.ArrayList<BookId>();
        for (int i = 0; i < 8_000; i++) ids.add(BookId.generate());

        assertThatThrownBy(() -> LocalCopyDeletionRecoveryStore.prepare(
                        root.resolve("book.fb2"), root.resolve(".book.fb2.mhl-delete-test.recovery"),
                        root, "collection-test", ids, "0".repeat(64)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("recovery read limit");

        // No published unreadable .pending marker, and no abandoned private workspace.
        Path directory = LocalCopyDeletionRecoveryStore.recoveryDir();
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(180)
    void startupRecoveryStreamsHundredsOfMarkersWhileRestoringEveryBook() throws Exception {
        final int count = 320;
        Path root = Files.createDirectories(temp.resolve("library-batched-recovery"));
        Path database = temp.resolve("catalog-batched-recovery.db");
        var ids = new java.util.ArrayList<BookId>();
        for (int i = 0; i < count; i++) ids.add(BookId.generate());

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE books (id TEXT PRIMARY KEY, local INTEGER NOT NULL)");
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("INSERT INTO books(id, local) VALUES (?, 1)")) {
                for (BookId id : ids) {
                    insert.setString(1, id.asString());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        }

        for (int i = 0; i < count; i++) {
            Path book = root.resolve("book-" + i + ".fb2");
            Files.writeString(book, "recover " + i);
            resolver.stagePhysicalFileForDeletion(book, root, "collection-test", List.of(ids.get(i)));
        }
        assertThat(pendingMarkers()).hasSize(count);

        LocalCopyDeletionRecoveryStore.recoverForDatabase("collection-test", database);

        assertThat(pendingMarkers()).isEmpty();
        for (int i = 0; i < count; i++) {
            assertThat(Files.readString(root.resolve("book-" + i + ".fb2")))
                    .isEqualTo("recover " + i);
        }
    }

    @Test
    void startupRecoveryRestoresBytesWhenCatalogTransactionDidNotCommit() throws Exception {
        BookId id = BookId.generate();
        Path root = Files.createDirectories(temp.resolve("library"));
        Path book = root.resolve("book.fb2");
        Files.writeString(book, "recover me");
        Path database = createDatabase(id, true);

        BookResourcePort.StagedDeletion staged = resolver.stagePhysicalFileForDeletion(book, root, "collection-test", List.of(id));
        Path recovery = staged.recoveryPath();
        assertThat(book).doesNotExist();
        assertThat(recovery).exists();
        assertThat(pendingMarkers()).hasSize(1);

        // Simulate abrupt process termination: no in-memory rollback/commit handle is invoked.
        LocalCopyDeletionRecoveryStore.recoverForDatabase("collection-test", database);

        assertThat(book).exists();
        assertThat(Files.readString(book)).isEqualTo("recover me");
        assertThat(recovery).doesNotExist();
        assertThat(pendingMarkers()).isEmpty();
    }

    @Test
    void startupRecoveryReleasesHiddenBytesWhenCatalogTransactionCommitted() throws Exception {
        BookId id = BookId.generate();
        Path root = Files.createDirectories(temp.resolve("library-committed"));
        Path book = root.resolve("book.fb2");
        Files.writeString(book, "remove me");
        Path database = createDatabase(id, false);

        BookResourcePort.StagedDeletion staged = resolver.stagePhysicalFileForDeletion(book, root, "collection-test", List.of(id));
        Path recovery = staged.recoveryPath();

        LocalCopyDeletionRecoveryStore.recoverForDatabase("collection-test", database);

        assertThat(book).doesNotExist();
        assertThat(recovery).doesNotExist();
        assertThat(pendingMarkers()).isEmpty();
    }

    @Test
    void markerForDifferentCollectionIsLeftUntouched() throws Exception {
        BookId markerBook = BookId.generate();
        BookId databaseBook = BookId.generate();
        Path root = Files.createDirectories(temp.resolve("library-other"));
        Path book = root.resolve("book.fb2");
        Files.writeString(book, "other collection");
        Path database = createDatabase(databaseBook, true);

        BookResourcePort.StagedDeletion staged = resolver.stagePhysicalFileForDeletion(book, root, "marker-collection", List.of(markerBook));

        LocalCopyDeletionRecoveryStore.recoverForDatabase("database-collection", database);

        assertThat(book).doesNotExist();
        assertThat(staged.recoveryPath()).exists();
        assertThat(pendingMarkers()).hasSize(1);
        staged.rollback();
    }

    @Test
    void commitDoesNotDeleteRecoveryFileReplacedByForeignBytes() throws Exception {
        BookId id = BookId.generate();
        Path root = Files.createDirectories(temp.resolve("library-commit-race"));
        Path book = root.resolve("book.fb2");
        Files.writeString(book, "owned bytes");

        BookResourcePort.StagedDeletion staged = resolver.stagePhysicalFileForDeletion(
                book, root, "collection-test", List.of(id));
        Path recovery = staged.recoveryPath();
        Files.writeString(recovery, "FOREIGN REPLACEMENT");

        assertThatThrownBy(staged::commit)
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("changed before detach");

        assertThat(book).doesNotExist();
        assertThat(Files.readString(recovery)).isEqualTo("FOREIGN REPLACEMENT");
        assertThat(pendingMarkers()).hasSize(1);
    }

    @Test
    void startupRecoveryDoesNotDeleteForeignRecoveryWhenCatalogCommitted() throws Exception {
        BookId id = BookId.generate();
        Path root = Files.createDirectories(temp.resolve("library-foreign-recovery"));
        Path book = root.resolve("book.fb2");
        Files.writeString(book, "owned bytes");
        Path database = createDatabase(id, false);

        BookResourcePort.StagedDeletion staged = resolver.stagePhysicalFileForDeletion(
                book, root, "collection-test", List.of(id));
        Path recovery = staged.recoveryPath();
        Files.writeString(recovery, "FOREIGN RECOVERY");

        assertThatThrownBy(() -> LocalCopyDeletionRecoveryStore.recoverForDatabase("collection-test", database))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("changed before detach");

        assertThat(book).doesNotExist();
        assertThat(Files.readString(recovery)).isEqualTo("FOREIGN RECOVERY");
        assertThat(pendingMarkers()).hasSize(1);
    }

    @Test
    void startupRecoveryDoesNotOverwriteForeignVisibleBook() throws Exception {
        BookId id = BookId.generate();
        Path root = Files.createDirectories(temp.resolve("library-foreign-visible"));
        Path book = root.resolve("book.fb2");
        Files.writeString(book, "owned bytes");
        Path database = createDatabase(id, true);

        BookResourcePort.StagedDeletion staged = resolver.stagePhysicalFileForDeletion(
                book, root, "collection-test", List.of(id));
        Path recovery = staged.recoveryPath();
        Files.writeString(book, "FOREIGN VISIBLE BOOK");

        assertThatThrownBy(() -> LocalCopyDeletionRecoveryStore.recoverForDatabase("collection-test", database))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Visible book changed");

        assertThat(Files.readString(book)).isEqualTo("FOREIGN VISIBLE BOOK");
        assertThat(Files.readString(recovery)).isEqualTo("owned bytes");
        assertThat(pendingMarkers()).hasSize(1);
    }

    @Test
    void startupRecoveryRejectsSymlinkMarkerWithoutTouchingExternalFile() throws Exception {
        BookId id = BookId.generate();
        Path root = Files.createDirectories(temp.resolve("library-marker-symlink"));
        Path book = root.resolve("book.fb2");
        Files.writeString(book, "owned bytes");
        Path database = createDatabase(id, true);

        BookResourcePort.StagedDeletion staged = resolver.stagePhysicalFileForDeletion(
                book, root, "collection-test", List.of(id));
        Path marker = pendingMarkers().getFirst();
        Path external = temp.resolve("outside-marker.txt");
        Files.writeString(external, "FOREIGN MARKER TARGET");
        Files.delete(marker);
        Files.createSymbolicLink(marker, external);

        assertThatThrownBy(() -> LocalCopyDeletionRecoveryStore.recoverForDatabase("collection-test", database))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("symbolic link");

        assertThat(Files.readString(external)).isEqualTo("FOREIGN MARKER TARGET");
        assertThat(staged.recoveryPath()).exists();
        assertThat(book).doesNotExist();
        assertThat(Files.isSymbolicLink(marker)).isTrue();
    }

    @Test
    void startupRecoveryRejectsManagedRootReachedThroughSymlinkedParentWhenSupported() throws Exception {
        BookId id = BookId.generate();
        Path outsideParent = Files.createDirectories(temp.resolve("outside-parent"));
        Path realRoot = Files.createDirectories(outsideParent.resolve("library"));
        Path aliasParent = temp.resolve("alias-parent");
        try {
            Files.createSymbolicLink(aliasParent, outsideParent);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            return;
        }

        Path rootThroughLink = aliasParent.resolve("library");
        Path original = rootThroughLink.resolve("book.fb2");
        Path recovery = rootThroughLink.resolve(".book.fb2.mhl-delete-parent-link.recovery");
        Files.writeString(realRoot.resolve(recovery.getFileName()), "owned bytes");
        String contentHash = com.myhomelibcorp.application.util.SafeFilePublication.sha256(
                realRoot.resolve(recovery.getFileName()), "test recovery bytes");
        Path database = createDatabase(id, true);

        Path markerDir = Files.createDirectories(LocalCopyDeletionRecoveryStore.recoveryDir());
        Path marker = markerDir.resolve("parent-link.pending");
        Properties properties = new Properties();
        properties.setProperty("version", "2");
        properties.setProperty("original", original.toAbsolutePath().normalize().toString());
        properties.setProperty("recovery", recovery.toAbsolutePath().normalize().toString());
        properties.setProperty("managedRoot", rootThroughLink.toAbsolutePath().normalize().toString());
        properties.setProperty("collectionId", "collection-test");
        properties.setProperty("bookIds", id.asString());
        properties.setProperty("contentSha256", contentHash);
        try (var out = Files.newOutputStream(marker)) {
            properties.store(out, "parent symlink marker");
        }

        assertThatThrownBy(() -> LocalCopyDeletionRecoveryStore.recoverForDatabase("collection-test", database))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("symbolic link");

        assertThat(marker).exists();
        assertThat(realRoot.resolve(recovery.getFileName())).exists();
    }

    @Test
    void legacyMarkerIsKeptAsEvidenceBecauseItCannotProveOwnership() throws Exception {
        BookId id = BookId.generate();
        Path root = Files.createDirectories(temp.resolve("library-legacy-marker"));
        Path book = root.resolve("book.fb2");
        Path recovery = root.resolve(".book.fb2.mhl-delete-legacy.recovery");
        Files.writeString(recovery, "legacy bytes");
        Path database = createDatabase(id, true);
        Path dir = Files.createDirectories(LocalCopyDeletionRecoveryStore.recoveryDir());
        Path marker = dir.resolve("legacy.pending");

        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("original", book.toAbsolutePath().normalize().toString());
        properties.setProperty("recovery", recovery.toAbsolutePath().normalize().toString());
        properties.setProperty("managedRoot", root.toAbsolutePath().normalize().toString());
        properties.setProperty("collectionId", "collection-test");
        properties.setProperty("bookIds", id.asString());
        try (var out = Files.newOutputStream(marker)) {
            properties.store(out, "legacy marker");
        }

        assertThatThrownBy(() -> LocalCopyDeletionRecoveryStore.recoverForDatabase("collection-test", database))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Legacy local-copy deletion marker cannot prove file ownership");

        assertThat(marker).exists();
        assertThat(recovery).exists();
        assertThat(book).doesNotExist();
    }

    private Path createDatabase(BookId id, boolean local) throws Exception {
        Path database = temp.resolve("catalog-" + id.asString() + ".db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE books (id TEXT PRIMARY KEY, local INTEGER NOT NULL)");
            try (var insert = connection.prepareStatement("INSERT INTO books(id, local) VALUES (?, ?)")) {
                insert.setString(1, id.asString());
                insert.setInt(2, local ? 1 : 0);
                insert.executeUpdate();
            }
        }
        return database;
    }

    private List<Path> pendingMarkers() throws Exception {
        Path dir = LocalCopyDeletionRecoveryStore.recoveryDir();
        if (!Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".pending")).toList();
        }
    }
}
