package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteBulkImportOptimizerTest {

    @TempDir
    Path tempDir;

    @Test
    void skipsConnectionScopedPragmasWhenNoTransactionOwnsTheConnection() {
        try (Fixture fixture = fixture("outside.db")) {
            long cacheBefore = pragma(fixture.jdbc, "PRAGMA cache_size");
            long mmapBefore = pragma(fixture.jdbc, "PRAGMA mmap_size");

            fixture.optimizer.enableBulkInsertMode();
            fixture.optimizer.disableBulkInsertMode();

            assertEquals(cacheBefore, pragma(fixture.jdbc, "PRAGMA cache_size"));
            assertEquals(mmapBefore, pragma(fixture.jdbc, "PRAGMA mmap_size"));
        }
    }

    @Test
    void tunesAndRestoresTheSameTransactionBoundConnection() {
        try (Fixture fixture = fixture("transaction.db")) {
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource));

            transaction.execute(status -> {
                long cacheBefore = pragma(fixture.jdbc, "PRAGMA cache_size");
                long mmapBefore = pragma(fixture.jdbc, "PRAGMA mmap_size");

                fixture.optimizer.enableBulkInsertMode();
                assertEquals(-32768L, pragma(fixture.jdbc, "PRAGMA cache_size"));
                assertEquals(67108864L, pragma(fixture.jdbc, "PRAGMA mmap_size"));

                // Nested callers keep a LIFO snapshot and never restore a value from another connection.
                fixture.optimizer.enableBulkInsertMode();
                fixture.optimizer.disableBulkInsertMode();
                assertEquals(-32768L, pragma(fixture.jdbc, "PRAGMA cache_size"));
                assertEquals(67108864L, pragma(fixture.jdbc, "PRAGMA mmap_size"));

                fixture.optimizer.disableBulkInsertMode();
                assertEquals(cacheBefore, pragma(fixture.jdbc, "PRAGMA cache_size"));
                assertEquals(mmapBefore, pragma(fixture.jdbc, "PRAGMA mmap_size"));
                return null;
            });
        }
    }

    private Fixture fixture(String dbName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve(dbName));
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        config.setConnectionInitSql(
                "PRAGMA synchronous=NORMAL; " +
                "PRAGMA temp_store=MEMORY; " +
                "PRAGMA cache_size=-8192; " +
                "PRAGMA mmap_size=268435456;"
        );
        HikariDataSource dataSource = new HikariDataSource(config);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS bulk_optimizer_probe(id INTEGER PRIMARY KEY)");

        CollectionManager collections = mock(CollectionManager.class);
        when(collections.getCurrentDataSource()).thenReturn(dataSource);
        when(collections.getCurrentJdbcTemplate()).thenReturn(jdbc);
        return new Fixture(dataSource, jdbc, new SqliteBulkImportOptimizer(collections));
    }

    private static long pragma(JdbcTemplate jdbc, String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        if (value == null) throw new AssertionError("No value for " + sql);
        return value;
    }

    private record Fixture(HikariDataSource dataSource,
                           JdbcTemplate jdbc,
                           SqliteBulkImportOptimizer optimizer) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
