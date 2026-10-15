package com.myhomelibcorp.infrastructure.config;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentCollectionTransactionManagerTest {
    @TempDir Path temp;

    @Test
    void nestedCollectionTransactionsUseSamePhysicalConnectionAndRollBackTogether() {
        SQLiteDataSource source = sqlite("nested.db");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE things (id INTEGER PRIMARY KEY)");
        CollectionManager collections = mock(CollectionManager.class);
        when(collections.getCurrentDataSource()).thenReturn(source);
        TransactionTemplate tx = new TransactionTemplate(new CurrentCollectionTransactionManager(collections));

        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            jdbc.update("INSERT INTO things(id) VALUES (1)");
            tx.execute(inner -> {
                jdbc.update("INSERT INTO things(id) VALUES (2)");
                return null;
            });
            throw new IllegalStateException("failed after second batch");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM things", Integer.class));
    }

    @Test
    void commitUsesOriginalPhysicalDataSourceEvenIfActiveSelectionChanges() {
        SQLiteDataSource a = sqlite("a.db");
        SQLiteDataSource b = sqlite("b.db");
        JdbcTemplate jdbcA = new JdbcTemplate(a), jdbcB = new JdbcTemplate(b);
        jdbcA.execute("CREATE TABLE things (id INTEGER PRIMARY KEY)");
        jdbcB.execute("CREATE TABLE things (id INTEGER PRIMARY KEY)");
        AtomicReference<DataSource> active = new AtomicReference<>(a);
        CollectionManager collections = mock(CollectionManager.class);
        when(collections.getCurrentDataSource()).thenAnswer(call -> active.get());
        TransactionTemplate tx = new TransactionTemplate(new CurrentCollectionTransactionManager(collections));

        tx.execute(status -> {
            jdbcA.update("INSERT INTO things(id) VALUES (1)");
            active.set(b);
            return null;
        });
        assertEquals(1, jdbcA.queryForObject("SELECT COUNT(*) FROM things", Integer.class));
        assertEquals(0, jdbcB.queryForObject("SELECT COUNT(*) FROM things", Integer.class));
    }

    private SQLiteDataSource sqlite(String name) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temp.resolve(name));
        return ds;
    }
}
