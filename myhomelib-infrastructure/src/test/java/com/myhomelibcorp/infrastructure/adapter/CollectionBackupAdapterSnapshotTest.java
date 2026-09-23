package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

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
        CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager);
        Path snapshot = tempDir.resolve("checkpoint.db");

        adapter.createDatabaseSnapshot(mock(Collection.class), snapshot);
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
    void recoveryCheckpointUsesFastPathAndStillProducesIndependentValidDatabase() throws Exception {
        Path liveDb = tempDir.resolve("live-recovery.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + liveDb.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE books(id TEXT PRIMARY KEY, title TEXT)");
        jdbc.update("INSERT INTO books(id,title) VALUES (?,?)", "b1", "Before");

        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager);
        Path checkpoint = tempDir.resolve("recovery.db");

        adapter.createRecoveryCheckpoint(mock(Collection.class), checkpoint);
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
        CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                adapter.createDatabaseSnapshot(mock(Collection.class), target))
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
