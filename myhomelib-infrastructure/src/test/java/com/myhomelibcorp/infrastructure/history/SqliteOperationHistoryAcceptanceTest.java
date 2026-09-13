package com.myhomelibcorp.infrastructure.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.bulkedit.BatchMetadataEditRule;
import com.myhomelibcorp.infrastructure.persistence.sqlite.TestCollectionManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteOperationHistoryAcceptanceTest {
    @TempDir Path tempDir;

    @Test
    void restartKeepsLatestUndoableAndReverseOrderGuard() {
        Path file = tempDir.resolve("history.db");
        var ds1 = new DriverManagerDataSource("jdbc:sqlite:" + file.toAbsolutePath());
        Flyway.configure().dataSource(ds1).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc1 = new JdbcTemplate(ds1);
        TestCollectionManager manager1 = manager(jdbc1, ds1);
        var history1 = new SqliteOperationHistoryAdapter(manager1, new ObjectMapper());
        history1.beginBulkOperation("older", "older", 1, List.<BatchMetadataEditRule>of());
        history1.completeOperation("older", 1);
        history1.beginBulkOperation("newer", "newer", 1, List.<BatchMetadataEditRule>of());
        history1.completeOperation("newer", 1);

        var ds2 = new DriverManagerDataSource("jdbc:sqlite:" + file.toAbsolutePath());
        JdbcTemplate jdbc2 = new JdbcTemplate(ds2);
        var history2 = new SqliteOperationHistoryAdapter(manager(jdbc2, ds2), new ObjectMapper());

        assertThat(history2.latestUndoable()).get().extracting(e -> e.operationId()).isEqualTo("newer");
        assertThatThrownBy(() -> history2.requireLatestUndoable("older"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reverse");
        history2.requireLatestUndoable("newer");
    }

    @Test
    void retentionIsBoundedAndNeverPrunesNewestUndoableEntry() {
        Path file = tempDir.resolve("retention.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + file.toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        var history = new SqliteOperationHistoryAdapter(manager(jdbc, ds), new ObjectMapper());
        for (int i = 1; i <= 205; i++) {
            String id = "op-" + i;
            history.beginBulkOperation(id, id, 1, List.of());
            history.completeOperation(id, 1);
        }
        history.prune(200);
        assertThat(history.recent(200)).hasSize(200);
        assertThat(history.latestUndoable()).get().extracting(e -> e.operationId()).isEqualTo("op-205");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_history", Integer.class)).isEqualTo(200);
    }

    @Test
    void retentionLowerBoundKeepsOnlyNewestUndoableOperation() {
        Path file = tempDir.resolve("retention-min.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + file.toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        var history = new SqliteOperationHistoryAdapter(manager(jdbc, ds), new ObjectMapper());
        for (int i = 1; i <= 3; i++) {
            String id = "min-" + i;
            history.beginBulkOperation(id, id, 1, List.of());
            history.completeOperation(id, 1);
        }

        history.prune(0);

        assertThat(history.recent(200)).extracting(e -> e.operationId()).containsExactly("min-3");
        assertThat(history.latestUndoable()).get().extracting(e -> e.operationId()).isEqualTo("min-3");
    }

    private static TestCollectionManager manager(JdbcTemplate jdbc, javax.sql.DataSource ds) {
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentJdbcTemplate(jdbc);
        manager.setCurrentDataSource(ds);
        return manager;
    }
}
