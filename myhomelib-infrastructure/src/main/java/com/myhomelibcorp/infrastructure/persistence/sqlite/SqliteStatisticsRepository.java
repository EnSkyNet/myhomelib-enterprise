package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteStatisticsRepository implements StatisticsRepository {

    private final CollectionManager collectionManager;
    private final QueryExecutor queryExecutor;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public LibraryStatistics getStatistics() {
        try {
            // FIX: використовуємо лише існуючі колонки (без languages_count тощо, якщо їх немає)
            String sql = "SELECT books_count, authors_count, series_count, genres_count, " +
                    "COALESCE(languages_count, 0) as languages_count, " +
                    "COALESCE(publishers_count, 0) as publishers_count, " +
                    "COALESCE(total_size_bytes, 0) as total_size_bytes, " +
                    "COALESCE(duplicates_count, 0) as duplicates_count, " +
                    "COALESCE(missing_covers_count, 0) as missing_covers_count " +
                    "FROM library_statistics WHERE id = 1";
            return queryExecutor.queryForObject(sql, (rs, rowNum) ->
                    LibraryStatistics.builder()
                            .booksCount(rs.getLong("books_count"))
                            .authorsCount(rs.getLong("authors_count"))
                            .seriesCount(rs.getLong("series_count"))
                            .genresCount(rs.getLong("genres_count"))
                            .languagesCount(rs.getLong("languages_count"))
                            .publishersCount(rs.getLong("publishers_count"))
                            .totalSizeBytes(rs.getLong("total_size_bytes"))
                            .duplicatesCount(rs.getLong("duplicates_count"))
                            .missingCoversCount(rs.getLong("missing_covers_count"))
                            .build()
            );
        } catch (Exception e) {
            log.warn("Failed to get statistics, returning empty", e);
            return LibraryStatistics.builder().build();
        }
    }

    @Override
    public void refreshStatistics() {
        JdbcTemplate jt = getJdbcTemplate();
        long books = queryExecutor.queryForLong("SELECT COUNT(*) FROM books");
        long authors = queryExecutor.queryForLong("SELECT COUNT(*) FROM authors");
        long series = queryExecutor.queryForLong("SELECT COUNT(*) FROM series");
        long genres = queryExecutor.queryForLong("SELECT COUNT(*) FROM genres");
        long languages = queryExecutor.queryForLong("SELECT COUNT(DISTINCT language) FROM books WHERE language IS NOT NULL");
        long publishers = 0;
        long totalSize = queryExecutor.queryForLong("SELECT COALESCE(SUM(file_size), 0) FROM books");
        long duplicates = 0;
        long missingCovers = 0;

        // FIX: використовуємо правильну кількість параметрів (9 значень + id=1)
        String sql = """
                INSERT OR REPLACE INTO library_statistics
                (id, books_count, authors_count, series_count, genres_count,
                 languages_count, publishers_count, total_size_bytes,
                 duplicates_count, missing_covers_count, last_updated)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        jt.update(sql, books, authors, series, genres, languages, publishers, totalSize, duplicates, missingCovers);
        log.info("Statistics refreshed: books={}, authors={}, series={}, genres={}", books, authors, series, genres);
    }
}