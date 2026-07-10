package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteStatisticsRepository {

    private final CollectionManager collectionManager;
    private final QueryExecutor queryExecutor;

    public void updateStatistics(long books, long authors, long genres, long series, long groups) {
        String sql = """
            UPDATE library_statistics
            SET books_count = ?, authors_count = ?, genres_count = ?, series_count = ?, groups_count = ?, last_updated = CURRENT_TIMESTAMP
            WHERE id = 1
            """;
        queryExecutor.update(sql, books, authors, genres, series, groups);
        log.info("Статистику оновлено: books={}, authors={}, genres={}, series={}, groups={}",
                books, authors, genres, series, groups);
    }

    public void refreshStatistics() {
        // Підраховуємо актуальні значення
        long books = queryExecutor.queryForLong("SELECT COUNT(*) FROM books");
        long authors = queryExecutor.queryForLong("SELECT COUNT(*) FROM authors");
        long genres = queryExecutor.queryForLong("SELECT COUNT(*) FROM genres");
        long series = queryExecutor.queryForLong("SELECT COUNT(*) FROM series");
        long groups = queryExecutor.queryForLong("SELECT COUNT(*) FROM groups");
        updateStatistics(books, authors, genres, series, groups);
    }

    public Statistics getStatistics() {
        String sql = "SELECT books_count, authors_count, genres_count, series_count, groups_count FROM library_statistics WHERE id = 1";
        return queryExecutor.queryForObject(sql, (rs, rowNum) -> new Statistics(
                rs.getLong("books_count"),
                rs.getLong("authors_count"),
                rs.getLong("genres_count"),
                rs.getLong("series_count"),
                rs.getLong("groups_count")
        ));
    }

    public record Statistics(long books, long authors, long genres, long series, long groups) {}
}