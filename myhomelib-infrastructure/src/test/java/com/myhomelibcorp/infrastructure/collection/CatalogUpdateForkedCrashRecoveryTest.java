package com.myhomelibcorp.infrastructure.collection;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.infrastructure.search.LuceneCollectionIndexLifecycle;
import com.myhomelibcorp.infrastructure.search.LuceneSearchService;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.CatalogUpdateRecoveryFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Kills a separate JVM, leaving real SQLite WAL/SHM files and a durable recovery marker.
 * A new connection after simulated restart must observe the appropriate catalog state.
 */
class CatalogUpdateForkedCrashRecoveryTest {
    @TempDir Path temp;

    @Test
    void restoresOrRetainsCatalogAtRealProcessCrashBoundaries() throws Exception {
        String previousDir = System.getProperty("myhomelib.dataDir");
        try {
            for (String point : List.of("BEFORE_MARKER", "AFTER_MARKER",
                    "DURING_TRANSACTION", "AFTER_SQLITE_COMMIT", "AFTER_MARKER_REMOVAL",
                    "AFTER_VERSION_COMMIT")) {
                Path root = Files.createDirectories(temp.resolve(point));
                System.setProperty("myhomelib.dataDir", root.resolve("app-data").toString());
                String collectionId = "catalog-forked-crash";
                Path target = root.resolve("catalog.db");
                Path checkpoint = CatalogUpdateRecoveryFiles.checkpoint(collectionId);
                Files.createDirectories(checkpoint.getParent());
                createDatabase(target);
                // The synthetic DB is closed and uses DELETE journaling here,
                // so a byte-for-byte copy is a consistent pre-update checkpoint.
                Files.copy(target, checkpoint);

                String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                String classpath = System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));
                Path log = root.resolve("crash-worker.log");
                Process child = new ProcessBuilder(java,
                        "-Dmyhomelib.dataDir=" + root.resolve("app-data"),
                        "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                        "-cp", classpath, CatalogUpdateCrashWorker.class.getName(),
                        target.toString(), collectionId, point)
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                boolean finished = child.waitFor(45, TimeUnit.SECONDS);
                if (!finished) {
                    child.destroyForcibly();
                    child.waitFor();
                }
                String output = Files.readString(log);
                assertThat(finished).as(point + ": " + output).isTrue();
                assertThat(child.exitValue()).as(point + ": " + output).isEqualTo(83);
                assertThat(output).contains("Crash test worker: Аварійне завершення",
                        "Crash test worker stderr: Аварійне завершення");
                if (!"BEFORE_MARKER".equals(point)
                        && !"AFTER_MARKER_REMOVAL".equals(point)
                        && !"AFTER_VERSION_COMMIT".equals(point)) {
                    assertThat(CatalogUpdateRecoveryFiles.isPending(collectionId)).as(point).isTrue();
                }
                if ("AFTER_SQLITE_COMMIT".equals(point)
                        || "AFTER_MARKER_REMOVAL".equals(point)
                        || "AFTER_VERSION_COMMIT".equals(point)) {
                    Path wal = Path.of(target.toString() + "-wal");
                    assertThat(wal).as(point).isRegularFile();
                    assertThat(Files.size(wal)).as(point + " committed WAL size").isPositive();
                }

                // Simulate next application start before any connection opens the SQLite file.
                Collection collection = new Collection(collectionId, collectionId, null,
                        target.toAbsolutePath().toString(), 0, null, null, null, null);
                CollectionCrashRecovery.recoverBeforeOpen(collection, target);

                String expected = ("AFTER_MARKER_REMOVAL".equals(point)
                        || "AFTER_VERSION_COMMIT".equals(point))
                        ? "partially-updated" : "baseline-before-update";
                try (var connection = DriverManager.getConnection("jdbc:sqlite:" + target.toAbsolutePath());
                     var statement = connection.createStatement()) {
                    try (var result = statement.executeQuery("SELECT value FROM recovery_state")) {
                        assertThat(result.next()).as(point).isTrue();
                        assertThat(result.getString(1)).as(point).isEqualTo(expected);
                    }
                    try (var result = statement.executeQuery("PRAGMA integrity_check")) {
                        assertThat(result.next()).as(point).isTrue();
                        assertThat(result.getString(1)).as(point).isEqualTo("ok");
                        assertThat(result.next()).as(point).isFalse();
                    }
                }
                assertThat(CatalogUpdateRecoveryFiles.isPending(collectionId)).as(point).isFalse();
                String expectedAppliedVersion = "AFTER_VERSION_COMMIT".equals(point) ? "v2" : "v1";
                try (var connection = DriverManager.getConnection("jdbc:sqlite:" + target.toAbsolutePath());
                     var statement = connection.createStatement();
                     var result = statement.executeQuery(
                             "SELECT applied_version FROM catalog_sources WHERE source_key='source-1'")) {
                    assertThat(result.next()).as(point + " source metadata row").isTrue();
                    assertThat(result.getString(1)).as(point + " applied version").isEqualTo(expectedAppliedVersion);
                    assertThat(result.next()).as(point + " unique source metadata row").isFalse();
                }
                if (!"BEFORE_MARKER".equals(point) && !"AFTER_MARKER_REMOVAL".equals(point)
                        && !"AFTER_VERSION_COMMIT".equals(point)) {
                    assertThat(checkpoint).as(point).doesNotExist();
                }
            }
        } finally {
            if (previousDir == null) System.clearProperty("myhomelib.dataDir");
            else System.setProperty("myhomelib.dataDir", previousDir);
        }
    }

    @Test
    void rollbackAfterLuceneCommitInvalidatesFreshnessMarkerForInterruptedWal() throws Exception {
        String previousDir = System.getProperty("myhomelib.dataDir");
        Path root = Files.createDirectories(temp.resolve("lucene-after-crash"));
        Path appData = root.resolve("app-data");
        System.setProperty("myhomelib.dataDir", appData.toString());
        try {
            String collectionId = "catalog-lucene-crash";
            Path target = root.resolve("catalog.db");
            Path checkpoint = CatalogUpdateRecoveryFiles.checkpoint(collectionId);
            Files.createDirectories(checkpoint.getParent());
            createDatabase(target);
            Files.copy(target, checkpoint);

            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path"));
            Path log = root.resolve("lucene-crash-worker.log");
            Process child = new ProcessBuilder(java,
                    "-Dmyhomelib.dataDir=" + appData,
                    "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                    "-cp", classpath, CatalogUpdateCrashWorker.class.getName(),
                    target.toString(), collectionId, "AFTER_SQLITE_COMMIT")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean finished = child.waitFor(45, TimeUnit.SECONDS);
            if (!finished) {
                child.destroyForcibly();
                child.waitFor();
            }
            String output = Files.readString(log);
            assertThat(finished).as(output).isTrue();
            assertThat(child.exitValue()).as(output).isEqualTo(83);

            Path wal = Path.of(target + "-wal");
            Path shm = Path.of(target + "-shm");
            assertThat(wal).isRegularFile();
            assertThat(Files.size(wal)).isPositive();
            assertThat(shm).exists();

            // Simulate a successful Lucene commit against the post-import SQLite+WAL state.
            // The index document count stays constant; freshness must therefore rely on the
            // persisted database/WAL state, not count alone.
            LuceneSearchService search = mock(LuceneSearchService.class);
            BookQueryRepository books = mock(BookQueryRepository.class);
            when(search.getDocumentCount()).thenReturn(1);
            when(books.countAll()).thenReturn(1L);
            Collection collection = new Collection(collectionId, collectionId, null,
                    target.toAbsolutePath().toString(), 0, null, null, null, null);

            LuceneCollectionIndexLifecycle beforeRecovery =
                    new LuceneCollectionIndexLifecycle(search, books);
            assertThat(beforeRecovery.activateCollectionIndex(collection)).isFalse();
            beforeRecovery.markCurrentIndexSynchronized();

            Path stateFile = AppPaths.collectionSearchIndexStateFile(collectionId);
            String committedIndexState = Files.readString(stateFile);
            assertThat(committedIndexState).contains("|wal:")
                    .doesNotContain("|wal:clean")
                    .doesNotContain("DIRTY");

            // Startup restores the pre-update checkpoint and removes WAL/SHM sidecars.
            // A Lucene marker written for the interrupted, newer DB must not be reusable.
            CollectionCrashRecovery.recoverBeforeOpen(collection, target);
            assertThat(wal).doesNotExist();
            assertThat(shm).doesNotExist();
            assertThat(CatalogUpdateRecoveryFiles.isPending(collectionId)).isFalse();
            assertThat(checkpoint).doesNotExist();

            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + target.toAbsolutePath());
                 var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("SELECT value FROM recovery_state")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("baseline-before-update");
                }
                try (var result = statement.executeQuery("PRAGMA integrity_check")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("ok");
                    assertThat(result.next()).isFalse();
                }
            }

            LuceneCollectionIndexLifecycle afterRecovery =
                    new LuceneCollectionIndexLifecycle(search, books);
            assertThat(afterRecovery.activateCollectionIndex(collection)).isFalse();
            assertThat(Files.readString(stateFile).trim()).isEqualTo("DIRTY");
        } finally {
            if (previousDir == null) System.clearProperty("myhomelib.dataDir");
            else System.setProperty("myhomelib.dataDir", previousDir);
        }
    }

    private static void createDatabase(Path db) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE recovery_state(value TEXT NOT NULL)");
            statement.execute("INSERT INTO recovery_state(value) VALUES ('baseline-before-update')");
            statement.execute("CREATE TABLE catalog_sources(source_key TEXT PRIMARY KEY, applied_version TEXT NOT NULL)");
            statement.execute("INSERT INTO catalog_sources(source_key, applied_version) VALUES ('source-1', 'v1')");
        }
    }

}
