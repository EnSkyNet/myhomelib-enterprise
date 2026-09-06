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

import static org.assertj.core.api.Assertions.assertThat;
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
