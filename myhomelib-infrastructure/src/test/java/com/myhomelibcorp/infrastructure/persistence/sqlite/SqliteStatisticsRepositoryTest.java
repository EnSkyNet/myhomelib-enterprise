package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteStatisticsRepositoryTest {
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private SqliteStatisticsRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:file:statistics-" + UUID.randomUUID() + "?mode=memory&cache=shared");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setMaximumPoolSize(2);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        jdbc = new JdbcTemplate(dataSource);
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentCollection(new Collection("stats", "Stats", Path.of("."), null, 1,
                null, null, null, null));
        manager.setCurrentDataSource(dataSource);
        manager.setCurrentJdbcTemplate(jdbc);
        repository = new SqliteStatisticsRepository(manager, new QueryExecutor(manager));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void missingCacheRowIsReadOnlyUntilExplicitRefresh() {
        jdbc.update("DELETE FROM library_statistics");

        var stale = repository.getStatistics();

        assertThat(stale.isStale()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM library_statistics", Long.class)).isZero();

        repository.refreshStatistics();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM library_statistics", Long.class)).isEqualTo(1L);
        assertThat(repository.getStatistics().isStale()).isFalse();
    }

    @Test
    void aggregateRefreshPreservesLegacyNullDeletedSemantics() {
        insertBook("active", 0, "en", "Pub", 100, 1, 100, "");
        insertBook("legacy-null", null, "en", "Pub", 50, 0, 0, "cover");
        insertBook("deleted", 1, "uk", "Other", 200, 1, 100, "");

        repository.invalidate();
        repository.refreshStatistics();
        var stats = repository.getStatistics();

        assertThat(stats.getBooksCount()).isEqualTo(2);
        assertThat(stats.getLanguagesCount()).isEqualTo(1);
        assertThat(stats.getPublishersCount()).isEqualTo(1);
        assertThat(stats.getTotalSizeBytes()).isEqualTo(150);
        assertThat(stats.getLocalBooksCount()).isEqualTo(1);
        assertThat(stats.getRemoteBooksCount()).isEqualTo(1);
        assertThat(stats.getReadBooksCount()).isEqualTo(1);
        assertThat(stats.getUnreadBooksCount()).isEqualTo(1);
        assertThat(stats.getDeletedBooksCount()).isEqualTo(1);
        assertThat(stats.getMissingCoversCount()).isEqualTo(1);
    }

    @Test
    void countsDistinctSeriesFromActiveBookRowsInsteadOfEmptySeriesLookup() {
        // A typical INPX import fills books.series while leaving the lookup table empty.
        jdbc.update("INSERT INTO books(id,title,file_name,series,deleted) VALUES (?,?,?,?,?)",
                "a", "A", "a.fb2", "Chronicles", 0);
        jdbc.update("INSERT INTO books(id,title,file_name,series,deleted) VALUES (?,?,?,?,?)",
                "b", "B", "b.fb2", " chronicles ", 0);
        jdbc.update("INSERT INTO books(id,title,file_name,series,deleted) VALUES (?,?,?,?,?)",
                "c", "C", "c.fb2", "Another", null);
        jdbc.update("INSERT INTO books(id,title,file_name,series,deleted) VALUES (?,?,?,?,?)",
                "d", "D", "d.fb2", "Deleted Only", 1);
        jdbc.update("INSERT INTO books(id,title,file_name,series,deleted) VALUES (?,?,?,?,?)",
                "e", "E", "e.fb2", "  ", 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM series", Long.class)).isZero();

        repository.refreshStatistics();
        assertThat(repository.getStatistics().getSeriesCount()).isEqualTo(2);
        // Ensure the persisted cache row, not only a transient local variable, is correct.
        assertThat(jdbc.queryForObject("SELECT series_count FROM library_statistics WHERE id=1", Long.class))
                .isEqualTo(2L);
    }

    private void insertBook(String id, Integer deleted, String language, String publisher,
                            long fileSize, int local, int progress, String coverHash) {
        jdbc.update("""
                INSERT INTO books(id, title, file_name, deleted, language, publisher, file_size, local, progress, cover_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, "Title " + id, id + ".fb2", deleted, language, publisher,
                fileSize, local, progress, coverHash);
    }
}
