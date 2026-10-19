package com.myhomelibcorp.infrastructure.organizer;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.organizer.apply.GetLibraryOrganizationStatusUseCase;
import com.myhomelibcorp.organizer.apply.RecoveryDisposition;
import com.myhomelibcorp.organizer.apply.UndoLibraryOrganizationUseCase;
import com.myhomelibcorp.organizer.fs.LibraryOperationCrashManifestReader;
import com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace;
import com.myhomelibcorp.organizer.journal.OrganizationItemState;
import com.myhomelibcorp.organizer.journal.OrganizationOperationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real production Apply, SQLite WAL and manifest across abrupt JVM exits; only isolated files. */
class SqliteOrganizerApplyCrashIntegrationV27Test {
    @TempDir Path temp;

    @Test
    void applyCrashAtFilesystemSqlBoundariesCanBeVerifiedAndRolledBackAfterRestart() throws Exception {
        String previous = System.getProperty("myhomelib.dataDir");
        try {
            Path stateDir = Files.createDirectories(temp.resolve("sandbox-app-state"));
            System.setProperty("myhomelib.dataDir", stateDir.toString());
            for (String point : List.of("BEFORE_VALIDATED", "BEFORE_COMMITTED", "AFTER_COMMITTED")) {
                Path scenario = Files.createDirectories(temp.resolve(point));
                Path src = Files.createDirectories(scenario.resolve("src"));
                Path dst = Files.createDirectories(scenario.resolve("dst"));
                Path source = Files.writeString(src.resolve("book.fb2"), "production Apply crash fixture " + point);
                Path target = dst.resolve("book.fb2");
                String originalHash = hash(source);
                Path db = scenario.resolve("journal.sqlite");
                SQLiteDataSource ds = dataSource(db);
                JdbcTemplate jdbc = new JdbcTemplate(ds);
                createSchema(jdbc);
                UUID operationId = UUID.randomUUID();
                UUID itemId = UUID.randomUUID();
                String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
                // Windows anonymous process pipes have small buffers. Waiting for the child
                // before draining its verbose crash diagnostics can deadlock both sides.
                // Redirect into this isolated scenario to keep the worker unblocked and
                // preserve the actual diagnostic output for any failed assertion.
                Path childLog = scenario.resolve("crash-worker.log");
                Process child = new ProcessBuilder(java, "-Dmyhomelib.dataDir=" + stateDir,
                        "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                        "-cp", cp, SqliteOrganizerApplyCrashWorkerV27.class.getName(),
                        db.toString(), source.toString(), target.toString(), originalHash,
                        operationId.toString(), itemId.toString(), point)
                        .redirectErrorStream(true).redirectOutput(childLog.toFile()).start();
                boolean ended = child.waitFor(45, TimeUnit.SECONDS);
                if (!ended) {child.destroyForcibly();child.waitFor();}
                String output = Files.readString(childLog);
                assertThat(ended).as(point + ": " + output).isTrue();
                assertThat(child.exitValue()).as(point + ": " + output).isEqualTo(83);
                assertThat(output).contains("stdout: Аварійне відновлення", "stderr: Аварійне відновлення");
                // A fresh adapter and connection must observe the persisted SQLite WAL state.
                SQLiteDataSource reopened = dataSource(db);
                JdbcTemplate fresh = new JdbcTemplate(reopened);
                SqliteOrganizationJournalAdapter adapter = new SqliteOrganizationJournalAdapter(fresh, reopened, null);
                OrganizationItemState expected = switch (point) {
                    case "BEFORE_VALIDATED" -> OrganizationItemState.STAGING;
                    case "BEFORE_COMMITTED" -> OrganizationItemState.VALIDATED;
                    default -> OrganizationItemState.COMMITTED;
                };
                assertThat(adapter.items(operationId)).singleElement().satisfies(item -> {
                    assertThat(item.state()).isEqualTo(expected);
                    assertThat(item.sourceSha256()).isEqualTo(originalHash);
                });
                assertThat(fresh.queryForObject("PRAGMA integrity_check", String.class)).isEqualTo("ok");
                assertThat(adapter.findOperation(operationId).orElseThrow().state())
                        .isEqualTo(OrganizationOperationState.APPLYING);
                if (point.equals("BEFORE_VALIDATED")) {
                    assertThat(hash(source)).isEqualTo(originalHash);
                    assertThat(target).doesNotExist();
                } else {
                    assertThat(source).doesNotExist();
                    assertThat(hash(target)).isEqualTo(originalHash);
                }
                assertThat(LibraryOperationWorkspace.manifestFor(operationId)).isRegularFile();
                assertThat(new LibraryOperationCrashManifestReader()
                        .read(LibraryOperationWorkspace.manifestFor(operationId)).operationId()).isEqualTo(operationId);
                var status = new GetLibraryOrganizationStatusUseCase(adapter,
                        new LibraryOperationCrashManifestReader()).incomplete().stream()
                        .filter(candidate -> candidate.operation().operationId().equals(operationId))
                        .findFirst().orElseThrow();
                assertThat(status.disposition()).as(point).isEqualTo(RecoveryDisposition.ROLLBACK_AVAILABLE);
                assertThat(new UndoLibraryOrganizationUseCase(adapter, new LibraryOperationCoordinator(), List.of())
                        .undo(operationId)).as(point).isEqualTo(1);
                assertThat(hash(source)).as(point).isEqualTo(originalHash);
                assertThat(target).as(point).doesNotExist();
                assertThat(adapter.items(operationId)).singleElement()
                        .satisfies(item -> assertThat(item.state()).isEqualTo(OrganizationItemState.ROLLED_BACK));
            }
        } finally {
            if (previous == null) System.clearProperty("myhomelib.dataDir");
            else System.setProperty("myhomelib.dataDir", previous);
        }
    }

    private static SQLiteDataSource dataSource(Path db) {
        SQLiteDataSource ds = new SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + db); return ds;
    }
    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("PRAGMA synchronous=FULL");
        jdbc.execute("""
                CREATE TABLE library_organization_operations (
                    id TEXT PRIMARY KEY, collection_id TEXT NOT NULL, profile_id TEXT NOT NULL,
                    source_root TEXT, destination_root TEXT, state TEXT NOT NULL,
                    started_at TEXT, completed_at TEXT, summary_json TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE library_organization_items (
                    operation_id TEXT NOT NULL, item_id TEXT NOT NULL, source_path TEXT NOT NULL,
                    source_entry TEXT, target_path TEXT, source_sha256 TEXT, target_sha256 TEXT,
                    action TEXT NOT NULL, state TEXT NOT NULL, backup_path TEXT,
                    optimization_profile TEXT, size_before INTEGER, size_after INTEGER,
                    bytes_saved INTEGER, percent_saved REAL, optimization_summary_json TEXT,
                    target_standard TEXT, device_profile TEXT, compression_backend TEXT,
                    validation_status TEXT, error_code TEXT, error_message TEXT,
                    PRIMARY KEY(operation_id, item_id))
                """);
    }
    private static String hash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] block = new byte[8192];
            for (int n; (n = in.read(block)) != -1;) if (n > 0) digest.update(block, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
