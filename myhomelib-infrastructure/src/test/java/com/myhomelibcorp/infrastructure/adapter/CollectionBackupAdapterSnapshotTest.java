package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CollectionBackupAdapterSnapshotTest {

    @TempDir Path tempDir;

    @Test
    void onlineBackupCreatesIndependentValidSnapshot() throws Exception {
        Path liveDb = tempDir.resolve("live.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + liveDb.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sample(id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
        jdbc.update("INSERT INTO sample(value) VALUES (?)", "before");

        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        Collection requested = identifiedCollection("c1");
        when(manager.getCurrentCollection()).thenReturn(requested);
        CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager);
        Path snapshot = tempDir.resolve("checkpoint.db");

        adapter.createDatabaseSnapshot(requested, snapshot);
        jdbc.update("UPDATE sample SET value = ? WHERE id = 1", "after");

        assertThat(Files.size(snapshot)).isGreaterThan(0L);
        adapter.validateDatabaseFile(snapshot);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + snapshot.toAbsolutePath());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT value FROM sample WHERE id = 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("before");
        }
    }
    @Test
    void recoveryCheckpointCreatesIndependentFullyValidatedDatabase() throws Exception {
        Path liveDb = tempDir.resolve("live-recovery.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + liveDb.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE books(id TEXT PRIMARY KEY, title TEXT)");
        jdbc.update("INSERT INTO books(id,title) VALUES (?,?)", "b1", "Before");

        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        Collection requested = identifiedCollection("c1");
        when(manager.getCurrentCollection()).thenReturn(requested);
        CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager);
        Path checkpoint = tempDir.resolve("recovery.db");

        adapter.createRecoveryCheckpoint(requested, checkpoint);
        jdbc.update("UPDATE books SET title='After' WHERE id='b1'");

        // Full validation after the fact proves the fast creation path still emitted a valid DB.
        adapter.validateDatabaseFile(checkpoint);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + checkpoint.toAbsolutePath());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT title FROM books WHERE id='b1'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("Before");
        }
    }

    @Test
    void recoveryCheckpointRefusesCorruptSecondaryIndexBeforePublishing() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            int start = sql.indexOf("TO '") + 4;
            int end = sql.lastIndexOf("'");
            Path staged = Path.of(sql.substring(start, end));
            createCorruptSecondaryIndexDatabase(staged);
            return null;
        }).when(jdbc).execute(anyString());

        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        Collection requested = identifiedCollection("c1");
        when(manager.getCurrentCollection()).thenReturn(requested);
        CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager);
        Path checkpoint = tempDir.resolve("corrupt-recovery.db");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                adapter.createRecoveryCheckpoint(requested, checkpoint))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.exists(checkpoint)).isFalse();
        try (var children = Files.list(tempDir)) {
            assertThat(children.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".mhl-snapshot-"))
                    .toList()).isEmpty();
        }
    }

    @Test
    void databaseValidationRefusesSymlinkedSqliteSource() throws Exception {
        Path outside = tempDir.resolve("outside-valid.db");
        createSimpleDatabase(outside, "outside");
        Path link = tempDir.resolve("linked.db");
        Assumptions.assumeTrue(canCreateSymlink(link, outside));

        CollectionBackupAdapter adapter = new CollectionBackupAdapter(mock(CollectionManager.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.validateDatabaseFile(link))
                .isInstanceOf(java.io.IOException.class);
        adapter.validateDatabaseFile(outside);
    }

    @Test
    void snapshotRefusesConcurrentDestinationReplacement() throws Exception {
        Path target = tempDir.resolve("checkpoint-race.db");
        Files.writeString(target, "ORIGINAL");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            int start = sql.indexOf("TO '") + 4;
            int end = sql.lastIndexOf("'");
            Path staged = Path.of(sql.substring(start, end));
            createSimpleDatabase(staged, "snapshot");
            Files.writeString(target, "FOREIGN");
            return null;
        }).when(jdbc).execute(anyString());

        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        Collection requested = identifiedCollection("c1");
        when(manager.getCurrentCollection()).thenReturn(requested);
        CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                adapter.createDatabaseSnapshot(requested, target))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("target changed before detach");
        assertThat(Files.readString(target)).isEqualTo("FOREIGN");
    }

    @Test
    void rollbackPreservesConcurrentLiveDatabaseReplacementAndOldEvidence() throws Exception {
        Path target = tempDir.resolve("live-rollback.db");
        Path snapshot = tempDir.resolve("snapshot-rollback.db");
        createSimpleDatabase(target, "old");
        createSimpleDatabase(snapshot, "checkpoint");
        Collection collection = new Collection("c1", "Main", null, target.toString(), 0,
                null, null, null, null);

        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentCollection()).thenReturn(collection);
        when(manager.hasActiveCollection()).thenReturn(true);
        doAnswer(invocation -> {
            Files.writeString(target, "FOREIGN");
            return null;
        }).when(manager).switchToCollection(collection);
        CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                adapter.restoreDatabaseSnapshot(collection, snapshot))
                .isInstanceOf(java.io.IOException.class);

        assertThat(Files.readString(target)).isEqualTo("FOREIGN");
        try (var files = Files.list(tempDir)) {
            var evidence = files.filter(path -> path.getFileName().toString().contains(".mhl-evidence-"))
                    .toList();
            assertThat(evidence).hasSize(1);
            adapter.validateDatabaseFile(evidence.getFirst());
        }
    }

    @Test
    void rollbackMustNotCloseDifferentActiveCollection() throws Exception {
        Path target = tempDir.resolve("collection-a.db");
        Path snapshot = tempDir.resolve("checkpoint-a.db");
        createSimpleDatabase(target, "original-a");
        createSimpleDatabase(snapshot, "backup-a");
        Collection requested = new Collection("a", "A", null, target.toString(), 0,
                null, null, null, null);
        Collection active = new Collection("b", "B", null, tempDir.resolve("b.db").toString(), 0,
                null, null, null, null);
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentCollection()).thenReturn(active);
        CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                adapter.restoreDatabaseSnapshot(requested, snapshot))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("requested collection is not active");
        verify(manager, never()).closeCurrentCollection();
        verify(manager, never()).switchToCollection(any());
        assertDatabaseValue(target, "original-a");
        assertNoRollbackWorkspace();
    }

    @Test
    void failedStagingValidationCleansWorkspaceWithoutTouchingActiveDatabase() throws Exception {
        Path target = tempDir.resolve("collection-validation.db");
        Path snapshot = tempDir.resolve("checkpoint-validation.db");
        createSimpleDatabase(target, "original");
        createSimpleDatabase(snapshot, "backup");
        Collection requested = new Collection("a", "A", null, target.toString(), 0,
                null, null, null, null);
        CollectionManager manager = mock(CollectionManager.class);
        CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager) {
            @Override
            public void validateDatabaseFile(Path file) throws java.io.IOException {
                super.validateDatabaseFile(file);
                if ("candidate.db".equals(file.getFileName().toString())) {
                    throw new java.io.IOException("Simulated staged database validation failure");
                }
            }
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                adapter.restoreDatabaseSnapshot(requested, snapshot))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Simulated staged database validation failure");
        verify(manager, never()).closeCurrentCollection();
        assertDatabaseValue(target, "original");
        assertNoRollbackWorkspace();
    }

    @Test
    void failedCloseBeforeSwapDoesNotAttemptSecondCloseOrTouchDatabase() throws Exception {
        Path target = tempDir.resolve("collection-close.db");
        Path snapshot = tempDir.resolve("checkpoint-close.db");
        createSimpleDatabase(target, "original");
        createSimpleDatabase(snapshot, "backup");
        Collection requested = new Collection("a", "A", null, target.toString(), 0,
                null, null, null, null);
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentCollection()).thenReturn(requested);
        doThrow(new IllegalStateException("close rejected")).when(manager).closeCurrentCollection();
        CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                adapter.restoreDatabaseSnapshot(requested, snapshot))
                .isInstanceOf(java.io.IOException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        verify(manager, times(1)).closeCurrentCollection();
        assertDatabaseValue(target, "original");
        assertNoRollbackWorkspace();
    }

    @Test
    void snapshotRefusesToBackupAnotherActiveCollection() throws Exception {
        Collection requested = identifiedCollection("a");
        Collection active = identifiedCollection("b");
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentCollection()).thenReturn(active);
        CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager);
        Path target = tempDir.resolve("wrong-collection.db");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                adapter.createDatabaseSnapshot(requested, target))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("requested collection is not active");
        verify(manager, never()).getCurrentJdbcTemplate();
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void snapshotRefusesPublicationIfActiveCollectionChangesDuringBackup() throws Exception {
        Collection requested = identifiedCollection("a");
        Collection another = identifiedCollection("b");
        java.util.concurrent.atomic.AtomicReference<Collection> current =
                new java.util.concurrent.atomic.AtomicReference<>(requested);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Path staged = Path.of(sql.substring(sql.indexOf("TO '") + 4, sql.lastIndexOf("'")));
            createSimpleDatabase(staged, "wrong-owner");
            current.set(another);
            return null;
        }).when(jdbc).execute(anyString());
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentCollection()).thenAnswer(invocation -> current.get());
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager);
        Path target = tempDir.resolve("switched-collection.db");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                adapter.createDatabaseSnapshot(requested, target))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("requested collection is not active");
        assertThat(Files.exists(target)).isFalse();
        try (var children = Files.list(tempDir)) {
            assertThat(children.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".mhl-snapshot-"))
                    .toList()).isEmpty();
        }
    }

    @Test
    void snapshotRefusesPublicationIfActiveCollectionSwitchesDuringIntegrityCheck() throws Exception {
        Collection requested = identifiedCollection("a");
        Collection another = identifiedCollection("b");
        java.util.concurrent.atomic.AtomicReference<Collection> current =
                new java.util.concurrent.atomic.AtomicReference<>(requested);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Path staged = Path.of(sql.substring(sql.indexOf("TO '") + 4, sql.lastIndexOf("'")));
            createSimpleDatabase(staged, "snapshot-for-a");
            return null;
        }).when(jdbc).execute(anyString());

        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentCollection()).thenAnswer(invocation -> current.get());
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager) {
            @Override
            public void validateDatabaseFile(Path databaseFile) throws java.io.IOException {
                super.validateDatabaseFile(databaseFile);
                // Simulate a direct integration that switches active collection
                // while a slow SQLite integrity_check is still finishing.
                current.set(another);
            }
        };
        Path target = tempDir.resolve("snapshot-after-switch.db");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> adapter.createDatabaseSnapshot(requested, target))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("requested collection is not active");
        assertThat(Files.exists(target)).isFalse();
        try (var children = Files.list(tempDir)) {
            assertThat(children.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith(".mhl-snapshot-"))
                    .toList()).isEmpty();
        }
    }

    private static Collection identifiedCollection(String id) {
        Collection collection = mock(Collection.class);
        when(collection.getId()).thenReturn(id);
        return collection;
    }

    private void assertNoRollbackWorkspace() throws Exception {
        try (var paths = Files.list(tempDir)) {
            assertThat(paths.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith(".mhl-catalog-rollback-"))
                    .toList()).isEmpty();
        }
    }

    private static void assertDatabaseValue(Path database, String expected) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT value FROM sample")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo(expected);
        }
    }

    private static void createCorruptSecondaryIndexDatabase(Path file) throws Exception {
        Files.deleteIfExists(file);
        int pageSize;
        long rootPage;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA page_size=4096");
            statement.execute("CREATE TABLE books(id INTEGER PRIMARY KEY, title TEXT NOT NULL)");
            statement.execute("CREATE INDEX idx_books_title ON books(title)");
            try (var prepared = connection.prepareStatement("INSERT INTO books(title) VALUES (?)")) {
                for (int i = 0; i < 2_000; i++) {
                    prepared.setString(1, "Synthetic title " + i);
                    prepared.addBatch();
                }
                prepared.executeBatch();
            }
            try (var result = statement.executeQuery("PRAGMA page_size")) {
                if (!result.next()) throw new IllegalStateException("SQLite page_size unavailable");
                pageSize = result.getInt(1);
            }
            try (var result = statement.executeQuery(
                    "SELECT rootpage FROM sqlite_master WHERE type='index' AND name='idx_books_title'")) {
                if (!result.next()) throw new IllegalStateException("SQLite secondary index missing");
                rootPage = result.getLong(1);
            }
        }
        // Preserve the SQLite header and schema page, but invalidate the secondary
        // index root page. Header/schema-only validation incorrectly accepts this DB.
        try (var random = new RandomAccessFile(file.toFile(), "rw")) {
            long offset = (rootPage - 1L) * pageSize;
            random.seek(offset);
            int original = random.read();
            if (original < 0 || original == 0) {
                throw new IllegalStateException("Unexpected SQLite secondary-index page header");
            }
            random.seek(offset);
            random.write(0);
            random.getFD().sync();
        }
    }

    private static boolean canCreateSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void createSimpleDatabase(Path file, String value) throws Exception {
        Files.deleteIfExists(file);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample(id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
            try (var prepared = connection.prepareStatement("INSERT INTO sample(value) VALUES (?)")) {
                prepared.setString(1, value);
                prepared.executeUpdate();
            }
        }
    }

}
