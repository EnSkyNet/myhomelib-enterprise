package com.myhomelibcorp.infrastructure.organizer;

import com.myhomelibcorp.organizer.journal.OrganizationItemState;
import com.myhomelibcorp.organizer.journal.OrganizationJournalItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
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

    private static OrganizationJournalItem item(UUID operation, UUID id, OrganizationItemState state) {
        return new OrganizationJournalItem(operation, id, Path.of(id + ".fb2"), null,
                "", "", "KEEP_AS_IS", state, null, "", 0, 0, 0, 0,
                "{}", "", "");
    }
}
