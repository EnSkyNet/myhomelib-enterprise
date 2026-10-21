package com.myhomelibcorp.infrastructure.persistence.sqlite;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the SQLite-backed 4 GB RAM watcher overflow on a large synthetic library.
 * Stress a 10,000-row persisted queue, not an unbounded in-memory collection of files.
 */
class IncomingFolderReplayIndexTest {
    private static final String NEXT_PAGE = """
            SELECT collection_id, file_path FROM incoming_folder_file
             WHERE status='WAITING'
               AND (collection_id>? OR (collection_id=? AND file_path>?))
             ORDER BY collection_id, file_path LIMIT ?
            """;

    @TempDir Path directory;

    @Test
    void tenThousandMixedStatusRowsReplayExactlyOnceUsingCoveringIndex() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("metadata.db").toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration_meta").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO collections(id,name) VALUES (?,?)", "c1", "Synthetic books");

        // 10,000 persistent paths: 2,000 WAITING and 8,000 already IMPORTED.
        // A full scan of imported rows for each batch would be a quadratic cost.
        jdbc.batchUpdate("""
                INSERT INTO incoming_folder_file(
                   collection_id, file_path, status, detected_at, updated_at)
                VALUES(?,?,?,'2026-10-21','2026-10-21')
                """, new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement stmt, int i) throws SQLException {
                stmt.setString(1, "c1");
                stmt.setString(2, String.format(java.util.Locale.ROOT, "book-%05d.fb2", i));
                stmt.setString(3, i % 5 == 0 ? "WAITING" : "IMPORTED");
            }
            @Override public int getBatchSize() { return 10_000; }
        });

        List<String> queryPlan = jdbc.query("EXPLAIN QUERY PLAN " + NEXT_PAGE,
                (rs, row) -> rs.getString("detail"), "c1", "c1", "", 128);
        assertThat(queryPlan).anySatisfy(detail ->
                assertThat(detail).contains("COVERING INDEX idx_incoming_folder_waiting_replay"));

        String cursor = "";
        List<String> seen = new ArrayList<>();
        int pages = 0;
        for (;;) {
            List<String> page = jdbc.query(NEXT_PAGE,
                    (rs, row) -> rs.getString("file_path"), "c1", "c1", cursor, 128);
            if (page.isEmpty()) break;
            assertThat(page).isSorted().hasSizeLessThanOrEqualTo(128);
            assertThat(page.getFirst()).isGreaterThan(cursor);
            seen.addAll(page);
            cursor = page.getLast();
            pages++;
            assertThat(pages).isLessThan(20); // A 2,000-row backlog needs 16 pages.
        }
        assertThat(pages).isEqualTo(16);
        assertThat(seen).hasSize(2_000).doesNotHaveDuplicates();
        assertThat(seen.getFirst()).isEqualTo("book-00000.fb2");
        assertThat(seen.getLast()).isEqualTo("book-09995.fb2");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incoming_folder_file WHERE status='IMPORTED'",
                Integer.class)).isEqualTo(8_000);
    }
}
