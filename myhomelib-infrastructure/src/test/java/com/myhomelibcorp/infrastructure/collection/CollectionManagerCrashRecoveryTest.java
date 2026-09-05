package com.myhomelibcorp.infrastructure.collection;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.config.DataSourceConfig;
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
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static String readState(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT value FROM recovery_state", String.class);
    }
}
