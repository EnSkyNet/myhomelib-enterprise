package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import com.zaxxer.hikari.HikariDataSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportIndexLifecycleTest {

    @Mock
    private CollectionManager collectionManager;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ImportIndexLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(collectionManager.hasActiveCollection()).thenReturn(true);
        when(collectionManager.getCurrentJdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString())).thenReturn(List.of());
        lifecycle = new ImportIndexLifecycle(collectionManager);
    }

    @Test
    void existingFullSnapshotKeepsLargeRebuildOnlyIndexesLive() {
        lifecycle.suspendForFullSnapshot(false);

        verifyIndexWasProbed("idx_books_title");
        verifyIndexWasProbed("idx_books_series");
        verifyIndexWasProbed("idx_authors_last_name");
        verifyIndexWasNotProbed("idx_books_active_id");
        verifyIndexWasNotProbed("idx_book_authors_author_id");
    }

    @Test
    void initialBaselineSuspendsAdditionalPureWriteIndexesButKeepsLookupIndexes() {
        lifecycle.suspendForFullSnapshot(true);

        verifyIndexWasProbed("idx_books_active_id");
        verifyIndexWasProbed("idx_books_active_language_title");
        verifyIndexWasProbed("idx_book_authors_author_id");
        verifyIndexWasProbed("idx_book_genres_genre_code");
        verifyIndexWasNotProbed("idx_authors_name_lookup");
        verifyIndexWasNotProbed("idx_keyword_books_book_id");
    }

    @Test
    void initialBaselineDropsAndRestoresExactLiveIndexesOnSqlite() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:sqlite:file:index-lifecycle-" + UUID.randomUUID() + "?mode=memory&cache=shared");
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setMaximumPoolSize(2);
        try {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            CollectionManager manager = mock(CollectionManager.class);
            when(manager.hasActiveCollection()).thenReturn(true);
            when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
            ImportIndexLifecycle realLifecycle = new ImportIndexLifecycle(manager);

            var token = realLifecycle.suspendForFullSnapshot(true);

            assertIndexPresent(jdbc, "idx_authors_name_lookup", true);
            assertIndexPresent(jdbc, "idx_keyword_books_book_id", true);
            assertIndexPresent(jdbc, "idx_books_title", false);
            assertIndexPresent(jdbc, "idx_books_active_id", false);
            assertIndexPresent(jdbc, "idx_book_authors_author_id", false);

            realLifecycle.restore(token);

            assertIndexPresent(jdbc, "idx_books_title", true);
            assertIndexPresent(jdbc, "idx_books_active_id", true);
            assertIndexPresent(jdbc, "idx_book_authors_author_id", true);
        } finally {
            ds.close();
        }
    }

    private static void assertIndexPresent(JdbcTemplate jdbc, String name, boolean expected) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?", Integer.class, name);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(expected ? 1 : 0);
    }

    @SuppressWarnings("unchecked")
    private void verifyIndexWasProbed(String indexName) {
        verify(jdbcTemplate).query(
                eq("SELECT name, sql FROM sqlite_master WHERE type='index' AND name=? AND sql IS NOT NULL"),
                any(RowMapper.class), eq(indexName));
    }

    @SuppressWarnings("unchecked")
    private void verifyIndexWasNotProbed(String indexName) {
        verify(jdbcTemplate, never()).query(
                eq("SELECT name, sql FROM sqlite_master WHERE type='index' AND name=? AND sql IS NOT NULL"),
                any(RowMapper.class), eq(indexName));
    }
}
