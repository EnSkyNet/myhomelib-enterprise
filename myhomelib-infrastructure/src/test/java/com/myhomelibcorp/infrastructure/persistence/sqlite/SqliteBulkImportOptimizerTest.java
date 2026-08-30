package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqliteBulkImportOptimizerTest {

    @Test
    void restoresExactPreviousPragmasAfterSuccessfulBulkMode() {
        CollectionManager collections = mock(CollectionManager.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(collections.getCurrentJdbcTemplate()).thenReturn(jdbc);
        when(jdbc.queryForObject("PRAGMA synchronous", Long.class)).thenReturn(2L);
        when(jdbc.queryForObject("PRAGMA temp_store", Long.class)).thenReturn(1L);
        when(jdbc.queryForObject("PRAGMA cache_size", Long.class)).thenReturn(-8192L);
        when(jdbc.queryForObject("PRAGMA mmap_size", Long.class)).thenReturn(268435456L);

        SqliteBulkImportOptimizer optimizer = new SqliteBulkImportOptimizer(collections);
        optimizer.enableBulkInsertMode();
        optimizer.disableBulkInsertMode();

        var order = inOrder(jdbc);
        order.verify(jdbc).execute("PRAGMA synchronous = NORMAL");
        order.verify(jdbc).execute("PRAGMA temp_store = MEMORY");
        order.verify(jdbc).execute("PRAGMA cache_size = -262144");
        order.verify(jdbc).execute("PRAGMA mmap_size = 2147483648");
        order.verify(jdbc).execute("PRAGMA synchronous = 2");
        order.verify(jdbc).execute("PRAGMA temp_store = 1");
        order.verify(jdbc).execute("PRAGMA cache_size = -8192");
        order.verify(jdbc).execute("PRAGMA mmap_size = 268435456");
    }

    @Test
    void partialEnableFailureRestoresSnapshotImmediately() {
        CollectionManager collections = mock(CollectionManager.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(collections.getCurrentJdbcTemplate()).thenReturn(jdbc);
        when(jdbc.queryForObject("PRAGMA synchronous", Long.class)).thenReturn(1L);
        when(jdbc.queryForObject("PRAGMA temp_store", Long.class)).thenReturn(0L);
        when(jdbc.queryForObject("PRAGMA cache_size", Long.class)).thenReturn(-2000L);
        when(jdbc.queryForObject("PRAGMA mmap_size", Long.class)).thenReturn(0L);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(jdbc).execute("PRAGMA temp_store = MEMORY");

        SqliteBulkImportOptimizer optimizer = new SqliteBulkImportOptimizer(collections);
        optimizer.enableBulkInsertMode();
        optimizer.disableBulkInsertMode();

        verify(jdbc).execute("PRAGMA synchronous = 1");
        verify(jdbc).execute("PRAGMA temp_store = 0");
        verify(jdbc).execute("PRAGMA cache_size = -2000");
        verify(jdbc).execute("PRAGMA mmap_size = 0");
    }
}
