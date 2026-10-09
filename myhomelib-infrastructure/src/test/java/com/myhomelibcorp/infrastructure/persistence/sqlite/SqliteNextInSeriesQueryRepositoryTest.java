package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class SqliteNextInSeriesQueryRepositoryTest {
    private Connection connection;
    private JdbcTemplate jdbc;
    private SqliteNextInSeriesQueryRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite::memory:");
        connection = sqlite.getConnection();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("""
                CREATE TABLE books(
                    id TEXT PRIMARY KEY,title TEXT NOT NULL,series TEXT,sequence_number INTEGER,
                    progress INTEGER DEFAULT 0,deleted INTEGER DEFAULT 0,missing_since TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE reading_progress(
                    book_id TEXT PRIMARY KEY,percent REAL NOT NULL DEFAULT 0,updated_at TEXT)
                """);
        CollectionManager manager = Mockito.mock(CollectionManager.class);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        repository = new SqliteNextInSeriesQueryRepository(manager);
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
    }

    @Test
    void oneCompletedShowsTwoAndTwoCompletedShowsThree() {
        book("a1", "A1", "Alpha", 1, 0, 0, null);
        book("a2", "A2", "Alpha", 2, 0, 0, null);
        book("a3", "A3", "Alpha", 3, 0, 0, null);
        progress("a1", 100);

        assertThat(repository.findNextInSeries(10)).extracting("bookId").containsExactly("a2");

        progress("a2", 100);
        assertThat(repository.findNextInSeries(10)).extracting("bookId").containsExactly("a3");
    }

    @Test
    void inProgressBookHidesWholeSeriesFromOnDeck() {
        book("a1", "A1", "Alpha", 1, 0, 0, null);
        book("a2", "A2", "Alpha", 2, 0, 0, null);
        book("a3", "A3", "Alpha", 3, 0, 0, null);
        progress("a1", 100);
        progress("a2", 37);

        assertThat(repository.findNextInSeries(10)).isEmpty();
    }

    @Test
    void unreadSeriesAndNullSequenceDoNotInventOrder() {
        book("u1", "U1", "Unread", 1, 0, 0, null);
        book("u2", "U2", "Unread", 2, 0, 0, null);
        book("n1", "N1", "NullSeq", 1, 0, 0, null);
        book("n2", "N2", "NullSeq", null, 0, 0, null);
        progress("n1", 100);

        assertThat(repository.findNextInSeries(10)).isEmpty();
    }

    @Test
    void ambiguousDuplicateCandidateSequenceIsExcludedFailSafe() {
        book("a1", "A1", "Alpha", 1, 100, 0, null);
        book("a2x", "A2 X", "Alpha", 2, 0, 0, null);
        book("a2y", "A2 Y", "alpha", 2, 0, 0, null);

        assertThat(repository.findNextInSeries(10)).isEmpty();
    }

    @Test
    void deletedOrMissingCandidatesAreHiddenAndNextAvailableSequenceWins() {
        book("a1", "A1", "Alpha", 1, 100, 0, null);
        book("a2", "A2", "Alpha", 2, 0, 1, null);
        book("a3", "A3", "Alpha", 3, 0, 0, "2026-10-01 10:00:00");
        book("a4", "A4", "Alpha", 4, 0, 0, null);

        assertThat(repository.findNextInSeries(10)).extracting("bookId").containsExactly("a4");
    }

    @Test
    void legacyBookProgressAlsoParticipatesInCompletedAndBlockedRules() {
        book("a1", "A1", "Alpha", 1, 100, 0, null);
        book("a2", "A2", "Alpha", 2, 20, 0, null);
        book("a3", "A3", "Alpha", 3, 0, 0, null);

        assertThat(repository.findNextInSeries(10)).isEmpty();

        jdbc.update("UPDATE books SET progress=100 WHERE id='a2'");
        assertThat(repository.findNextInSeries(10)).extracting("bookId").containsExactly("a3");
    }

    @Test
    void boundedLimitIsDeterministic() {
        for (int i = 0; i < 60; i++) {
            String series = String.format("Series-%02d", i);
            book("done-" + i, "Done", series, 1, 100, 0, null);
            book("next-" + i, "Next", series, 2, 0, 0, null);
        }

        var items = repository.findNextInSeries(500);

        assertThat(items).hasSize(50);
        assertThat(items.getFirst().series()).isEqualTo("Series-00");
        assertThat(items.getLast().series()).isEqualTo("Series-49");
    }

    private void book(String id, String title, String series, Integer sequence, int progress, int deleted, String missingSince) {
        jdbc.update("INSERT INTO books(id,title,series,sequence_number,progress,deleted,missing_since) VALUES(?,?,?,?,?,?,?)",
                id, title, series, sequence, progress, deleted, missingSince);
    }

    private void progress(String id, double percent) {
        jdbc.update("INSERT INTO reading_progress(book_id,percent,updated_at) VALUES(?,?,CURRENT_TIMESTAMP) " +
                "ON CONFLICT(book_id) DO UPDATE SET percent=excluded.percent,updated_at=excluded.updated_at", id, percent);
    }
}
