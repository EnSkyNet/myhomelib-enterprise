package com.myhomelibcorp.infrastructure.organizer;

import com.myhomelibcorp.organizer.journal.OrganizationItemState;
import com.myhomelibcorp.organizer.journal.OrganizationJournalOperation;
import com.myhomelibcorp.organizer.journal.OrganizationOperationState;
import com.myhomelibcorp.organizer.journal.OrganizationJournalItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteOrganizationJournalBatchTest {
    @TempDir Path temp;

    @Test
    void updatesSkippedBatchInSingleTransactionAndRollsBackOnSqlError() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temp.resolve("journal.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE library_organization_items (
                  operation_id TEXT NOT NULL, item_id TEXT NOT NULL, source_path TEXT NOT NULL,
                  source_entry TEXT, target_path TEXT, source_sha256 TEXT, target_sha256 TEXT,
                  action TEXT NOT NULL, state TEXT NOT NULL, backup_path TEXT,
                  optimization_profile TEXT, size_before INTEGER, size_after INTEGER,
                  bytes_saved INTEGER, percent_saved REAL, optimization_summary_json TEXT,
                  target_standard TEXT, device_profile TEXT, compression_backend TEXT,
                  validation_status TEXT, error_code TEXT, error_message TEXT,
                  PRIMARY KEY(operation_id, item_id),
                  CHECK(state IN ('PLANNED','SKIPPED'))
                )
                """);
        SqliteOrganizationJournalAdapter journal = new SqliteOrganizationJournalAdapter(jdbc, ds, null);
        UUID operation = UUID.randomUUID();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        journal.updateItem(item(operation, first, OrganizationItemState.PLANNED));
        journal.updateItem(item(operation, second, OrganizationItemState.PLANNED));

        assertThatThrownBy(() -> journal.updateItems(List.of(
                item(operation, first, OrganizationItemState.SKIPPED),
                item(operation, second, OrganizationItemState.FAILED))))
                .isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM library_organization_items WHERE state='PLANNED'", Integer.class))
                .isEqualTo(2);
        journal.updateItems(List.of(item(operation, first, OrganizationItemState.SKIPPED),
                item(operation, second, OrganizationItemState.SKIPPED)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM library_organization_items WHERE state='SKIPPED'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void findsCurrentJournalItemByOperationAndItemIdWithoutCrossOperationLeak() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temp.resolve("item-recheck.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE library_organization_items (
                  operation_id TEXT NOT NULL, item_id TEXT NOT NULL, source_path TEXT NOT NULL,
                  source_entry TEXT, target_path TEXT, source_sha256 TEXT, target_sha256 TEXT,
                  action TEXT NOT NULL, state TEXT NOT NULL, backup_path TEXT,
                  optimization_profile TEXT, size_before INTEGER, size_after INTEGER,
                  bytes_saved INTEGER, percent_saved REAL, optimization_summary_json TEXT,
                  target_standard TEXT, device_profile TEXT, compression_backend TEXT,
                  validation_status TEXT, error_code TEXT, error_message TEXT,
                  PRIMARY KEY(operation_id, item_id)
                )
                """);
        SqliteOrganizationJournalAdapter journal = new SqliteOrganizationJournalAdapter(jdbc, ds, null);
        UUID operation = UUID.randomUUID(), other = UUID.randomUUID(), id = UUID.randomUUID();
        journal.updateItem(item(operation, id, OrganizationItemState.PLANNED));
        journal.updateItem(item(other, id, OrganizationItemState.STAGING));
        assertThat(journal.findItem(operation, id)).get()
                .extracting(OrganizationJournalItem::state).isEqualTo(OrganizationItemState.PLANNED);
        assertThat(journal.findItem(other, id)).get()
                .extracting(OrganizationJournalItem::state).isEqualTo(OrganizationItemState.STAGING);
        journal.updateItem(item(operation, id, OrganizationItemState.VALIDATED));
        assertThat(journal.findItem(operation, id)).get()
                .extracting(OrganizationJournalItem::state).isEqualTo(OrganizationItemState.VALIDATED);
        assertThat(journal.findItem(operation, UUID.randomUUID())).isEmpty();
    }

    @Test
    void conditionalUndoUpdateRejectsConcurrentMetadataOrStateChangeWithoutUpserting() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temp.resolve("undo-cas.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
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
        SqliteOrganizationJournalAdapter journal = new SqliteOrganizationJournalAdapter(jdbc, ds, null);
        UUID operation = UUID.randomUUID(), id = UUID.randomUUID();
        OrganizationJournalItem expected = item(operation, id, OrganizationItemState.COMMITTED);
        OrganizationJournalItem rolledBack = item(operation, id, OrganizationItemState.ROLLED_BACK);
        journal.updateItem(expected);
        // Change one non-state field after the caller observed 'expected'.
        jdbc.update("UPDATE library_organization_items SET target_sha256=? WHERE operation_id=? AND item_id=?",
                "concurrent-digest", operation.toString(), id.toString());
        assertThat(journal.updateItemIfUnchanged(expected, rolledBack)).isFalse();
        assertThat(journal.findItem(operation, id)).get().extracting(OrganizationJournalItem::state)
                .isEqualTo(OrganizationItemState.COMMITTED);
        assertThat(journal.findItem(operation, id)).get().extracting(OrganizationJournalItem::targetSha256)
                .isEqualTo("concurrent-digest");
        jdbc.update("UPDATE library_organization_items SET target_sha256=? WHERE operation_id=? AND item_id=?",
                "", operation.toString(), id.toString());
        assertThat(journal.updateItemIfUnchanged(expected, rolledBack)).isTrue();
        assertThat(journal.findItem(operation, id)).get().extracting(OrganizationJournalItem::state)
                .isEqualTo(OrganizationItemState.ROLLED_BACK);
        assertThat(journal.updateItemIfUnchanged(expected, rolledBack)).isFalse();
        UUID missing = UUID.randomUUID();
        assertThat(journal.updateItemIfUnchanged(
                item(operation, missing, OrganizationItemState.COMMITTED),
                item(operation, missing, OrganizationItemState.ROLLED_BACK)))
                .isFalse();
    }

    @Test
    void findsAuthoritativeCompletedOperationIdentityById() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temp.resolve("operation-identity.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE library_organization_operations (
                  id TEXT PRIMARY KEY, collection_id TEXT NOT NULL, profile_id TEXT NOT NULL,
                  source_root TEXT, destination_root TEXT, state TEXT NOT NULL,
                  started_at TEXT, completed_at TEXT, summary_json TEXT)
                """);
        SqliteOrganizationJournalAdapter journal = new SqliteOrganizationJournalAdapter(jdbc, ds, null);
        UUID operationId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-10-17T12:00:00Z");
        journal.begin(new OrganizationJournalOperation(operationId, "safe", temp, temp,
                OrganizationOperationState.COMPLETED, startedAt, startedAt, "{}", "collection-one"), List.of());

        assertThat(journal.incompleteOperations()).isEmpty();
        assertThat(journal.findOperation(operationId)).isPresent()
                .get().extracting(OrganizationJournalOperation::collectionId).isEqualTo("collection-one");
        assertThat(journal.findOperation(UUID.randomUUID())).isEmpty();
    }

    @Test
    void atomicFinalizationRequiresExactOriginalItemSetAndCompletedStates() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temp.resolve("finalize-undo.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createFinalizationTables(jdbc);
        SqliteOrganizationJournalAdapter journal = new SqliteOrganizationJournalAdapter(jdbc, ds, null);
        UUID id = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        OrganizationJournalOperation expected = new OrganizationJournalOperation(id, "safe", temp, temp,
                OrganizationOperationState.FAILED, Instant.parse("2026-10-18T00:00:00Z"), null, "{}", "col1");
        journal.begin(expected, List.of(item(id, first, OrganizationItemState.ROLLED_BACK),
                item(id, second, OrganizationItemState.COMMITTED)));
        assertThat(journal.finalizeRollbackIfUnchanged(expected, List.of(first,second), "done")).isFalse();
        assertThat(journal.findOperation(id).orElseThrow().state()).isEqualTo(OrganizationOperationState.FAILED);

        journal.updateItem(item(id, second, OrganizationItemState.ROLLED_BACK));
        // Concurrent writer inserts an item after caller read the initial snapshot.
        UUID third = UUID.randomUUID();
        journal.updateItem(item(id, third, OrganizationItemState.ROLLED_BACK));
        assertThat(journal.finalizeRollbackIfUnchanged(expected, List.of(first,second), "done")).isFalse();
        jdbc.update("DELETE FROM library_organization_items WHERE operation_id=? AND item_id=?", id.toString(), third.toString());
        // Count still matches but a DIFFERENT item has replaced an initial one.
        jdbc.update("DELETE FROM library_organization_items WHERE operation_id=? AND item_id=?", id.toString(), second.toString());
        journal.updateItem(item(id, third, OrganizationItemState.ROLLED_BACK));
        assertThat(journal.finalizeRollbackIfUnchanged(expected, List.of(first,second), "done")).isFalse();
        jdbc.update("DELETE FROM library_organization_items WHERE operation_id=? AND item_id=?", id.toString(), third.toString());
        journal.updateItem(item(id, second, OrganizationItemState.ROLLED_BACK));
        // Concurrent operation metadata update also makes caller's snapshot stale.
        jdbc.update("UPDATE library_organization_operations SET summary_json='changed' WHERE id=?", id.toString());
        assertThat(journal.finalizeRollbackIfUnchanged(expected, List.of(first,second), "done")).isFalse();
        jdbc.update("UPDATE library_organization_operations SET summary_json='{}' WHERE id=?", id.toString());
        assertThat(journal.finalizeRollbackIfUnchanged(expected, List.of(first,second), "done")).isTrue();
        assertThat(journal.findOperation(id).orElseThrow().state()).isEqualTo(OrganizationOperationState.ROLLED_BACK);
        assertThat(journal.finalizeRollbackIfUnchanged(expected, List.of(first,second), "done")).isFalse();
    }

    @Test
    void atomicFinalizationRejectsDuplicateItemIdsAndNeverMutatesOnFailure() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temp.resolve("duplicate-finalize.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createFinalizationTables(jdbc);
        SqliteOrganizationJournalAdapter journal = new SqliteOrganizationJournalAdapter(jdbc, ds, null);
        UUID id = UUID.randomUUID(), first = UUID.randomUUID();
        OrganizationJournalOperation expected = new OrganizationJournalOperation(id, "safe", temp, temp,
                OrganizationOperationState.FAILED, Instant.parse("2026-10-18T00:00:00Z"), null, "{}", "col1");
        journal.begin(expected, List.of(item(id, first, OrganizationItemState.ROLLED_BACK)));
        assertThatThrownBy(() -> journal.finalizeRollbackIfUnchanged(expected, List.of(first, first), "done"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(journal.findOperation(id).orElseThrow().state()).isEqualTo(OrganizationOperationState.FAILED);
    }

    private static void createFinalizationTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE library_organization_operations (
                  id TEXT PRIMARY KEY, collection_id TEXT, profile_id TEXT,
                  source_root TEXT, destination_root TEXT, state TEXT,
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

    private static OrganizationJournalItem item(UUID operation, UUID id, OrganizationItemState state) {
        return new OrganizationJournalItem(operation, id, Path.of(id + ".fb2"), null,
                "", "", "KEEP_AS_IS", state, null, "", 0, 0, 0, 0,
                "{}", "", "");
    }
}
