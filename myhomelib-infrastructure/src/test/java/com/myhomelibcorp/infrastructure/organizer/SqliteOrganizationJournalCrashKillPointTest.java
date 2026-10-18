package com.myhomelibcorp.infrastructure.organizer;

import com.myhomelibcorp.organizer.apply.GetLibraryOrganizationStatusUseCase;
import com.myhomelibcorp.organizer.apply.RecoveryDisposition;
import com.myhomelibcorp.organizer.fs.LibraryOperationCrashManifestReader;
import com.myhomelibcorp.organizer.journal.OrganizationItemState;
import com.myhomelibcorp.organizer.journal.OrganizationJournalItem;
import com.myhomelibcorp.organizer.journal.OrganizationJournalOperation;
import com.myhomelibcorp.organizer.journal.OrganizationOperationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** WAL and physical output consistency after an actual hard process exit; isolated temporary database only. */
class SqliteOrganizationJournalCrashKillPointTest {
    @TempDir Path temporary;

    @Test
    void hardKillBeforeSqlCommitKeepsLastAuthoritativeStateAndRequiresReviewForBothCopies() throws Exception {
        Path db = temporary.resolve("journal.sqlite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
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
        UUID operationId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Path sourceRoot = Files.createDirectory(temporary.resolve("source"));
        Path targetRoot = Files.createDirectory(temporary.resolve("target"));
        Path source = Files.writeString(sourceRoot.resolve("book.fb2"), "crash-test-book-bytes");
        Path target = targetRoot.resolve("book.fb2");
        String sha256 = hash(source);
        SqliteOrganizationJournalAdapter adapter = new SqliteOrganizationJournalAdapter(jdbc, ds, null);
        adapter.begin(new OrganizationJournalOperation(operationId, "safe", sourceRoot, targetRoot,
                OrganizationOperationState.APPLYING, Instant.now(), null, "", "test-collection"),
                List.of(new OrganizationJournalItem(operationId, itemId, source, "", target, sha256, sha256,
                        "MOVE", OrganizationItemState.STAGING, null, "", 0, 0, 0, 0.0,
                        "{}", "", "")));

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String testCp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-cp", testCp,
                SqliteOrganizerCrashWorker.class.getName(), db.toString(),
                operationId.toString(), itemId.toString(), target.toString())
                .redirectErrorStream(true).start();
        boolean ended = process.waitFor(20, TimeUnit.SECONDS);
        if (!ended) { process.destroyForcibly(); process.waitFor(); }
        assertThat(ended).as("worker exit; output=%s", new String(process.getInputStream().readAllBytes()))
                .isTrue();
        assertThat(process.exitValue()).as("worker output: %s", new String(process.getInputStream().readAllBytes()))
                .isEqualTo(83);

        // This is a new adapter and a new SQLite connection, not the parent's cached result.
        SQLiteDataSource reopened = new SQLiteDataSource();
        reopened.setUrl("jdbc:sqlite:" + db);
        JdbcTemplate freshJdbc = new JdbcTemplate(reopened);
        SqliteOrganizationJournalAdapter recovered = new SqliteOrganizationJournalAdapter(freshJdbc, reopened, null);
        assertThat(recovered.items(operationId)).singleElement().satisfies(item -> {
            assertThat(item.state()).isEqualTo(OrganizationItemState.STAGING);
            assertThat(item.sourceSha256()).isEqualTo(sha256);
        });
        assertThat(freshJdbc.queryForObject("PRAGMA integrity_check", String.class)).isEqualTo("ok");
        assertThat(Files.exists(source)).isTrue();
        assertThat(Files.exists(target)).isTrue();
        assertThat(hash(target)).isEqualTo(sha256);
        var status = new GetLibraryOrganizationStatusUseCase(recovered,
                new LibraryOperationCrashManifestReader()).incomplete().stream()
                .filter(value -> value.operation().operationId().equals(operationId))
                .findFirst().orElseThrow();
        assertThat(status.disposition()).isEqualTo(RecoveryDisposition.MANUAL_REVIEW);
        assertThat(status.canRollback()).isFalse();
    }

    private static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) if (count > 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
