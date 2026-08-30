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
        String sql = "SELECT books_count, authors_count, series_count, genres_count, " +
                "COALESCE(languages_count, 0) AS languages_count, " +
                "COALESCE(publishers_count, 0) AS publishers_count, " +
                "COALESCE(total_size_bytes, 0) AS total_size_bytes, " +
                "COALESCE(duplicates_count, 0) AS duplicates_count, " +
                "COALESCE(missing_covers_count, 0) AS missing_covers_count, " +
                "COALESCE(local_books_count, 0) AS local_books_count, " +
                "COALESCE(remote_books_count, 0) AS remote_books_count, " +
                "COALESCE(read_books_count, 0) AS read_books_count, " +
                "COALESCE(unread_books_count, 0) AS unread_books_count, " +
                "COALESCE(favorites_count, 0) AS favorites_count, " +
                "COALESCE(deleted_books_count, 0) AS deleted_books_count, " +
                "COALESCE(sources_count, 0) AS sources_count " +
                "FROM library_statistics WHERE id = 1";
        LibraryStatistics stats = queryExecutor.queryForObject(sql, (rs, rowNum) ->
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
                        .localBooksCount(rs.getLong("local_books_count"))
                        .remoteBooksCount(rs.getLong("remote_books_count"))
                        .readBooksCount(rs.getLong("read_books_count"))
                        .unreadBooksCount(rs.getLong("unread_books_count"))
                        .favoritesCount(rs.getLong("favorites_count"))
                        .deletedBooksCount(rs.getLong("deleted_books_count"))
                        .sourcesCount(rs.getLong("sources_count"))
                        .build());
        if (stats == null) {
            refreshStatistics();
            stats = queryExecutor.queryForObject(sql, (rs, rowNum) ->
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
                            .localBooksCount(rs.getLong("local_books_count"))
                            .remoteBooksCount(rs.getLong("remote_books_count"))
                            .readBooksCount(rs.getLong("read_books_count"))
                            .unreadBooksCount(rs.getLong("unread_books_count"))
                            .favoritesCount(rs.getLong("favorites_count"))
                            .deletedBooksCount(rs.getLong("deleted_books_count"))
                            .sourcesCount(rs.getLong("sources_count"))
                            .build());
        }
        if (stats == null) throw new IllegalStateException("Statistics are unavailable for the active collection");
        return stats;
    }

    @Override
    public void invalidate() {
        // Deleting the single cache row is O(1). The next getStatistics() call rebuilds it
        // on the caller's background executor instead of running large COUNT/GROUP BY work
        // on every individual download/removal.
        getJdbcTemplate().update("DELETE FROM library_statistics WHERE id = 1");
        log.debug("Statistics cache invalidated for the active collection");
    }

    @Override
    public void refreshStatistics() {
        JdbcTemplate jt = getJdbcTemplate();
        long books = queryExecutor.queryForLong("SELECT COUNT(*) FROM books WHERE COALESCE(deleted,0)=0");
        long authors = queryExecutor.queryForLong("SELECT COUNT(*) FROM authors");
        long series = queryExecutor.queryForLong("SELECT COUNT(*) FROM series");
        long genres = queryExecutor.queryForLong("SELECT COUNT(*) FROM genres");
        long languages = queryExecutor.queryForLong("SELECT COUNT(DISTINCT language) FROM books WHERE COALESCE(deleted,0)=0 AND language IS NOT NULL AND TRIM(language)<>''");
        long publishers = queryExecutor.queryForLong("SELECT COUNT(DISTINCT publisher) FROM books WHERE COALESCE(deleted,0)=0 AND publisher IS NOT NULL AND TRIM(publisher)<>''");
        long totalSize = queryExecutor.queryForLong("SELECT COALESCE(SUM(file_size), 0) FROM books WHERE COALESCE(deleted,0)=0");
        long local = queryExecutor.queryForLong("SELECT COUNT(*) FROM books WHERE COALESCE(deleted,0)=0 AND COALESCE(local,0)=1");
        long remote = Math.max(0L, books - local);
        long read = queryExecutor.queryForLong("SELECT COUNT(*) FROM books WHERE COALESCE(deleted,0)=0 AND COALESCE(progress,0)>=100");
        long unread = Math.max(0L, books - read);
        long favourites = queryExecutor.queryForLong("SELECT COUNT(*) FROM book_groups bg JOIN groups g ON g.id=bg.group_id JOIN books b ON b.id=bg.book_id WHERE LOWER(g.name)='favorites' AND COALESCE(b.deleted,0)=0");
        long deleted = queryExecutor.queryForLong("SELECT COUNT(*) FROM books WHERE COALESCE(deleted,0)<>0");
        long sources = queryExecutor.queryForLong("SELECT COUNT(*) FROM catalog_sources");
        long duplicates = queryExecutor.queryForLong("""
                SELECT COALESCE(SUM(cnt - 1), 0)
                  FROM (
                        SELECT COUNT(*) AS cnt
                          FROM books
                         WHERE COALESCE(deleted,0)=0
                           AND TRIM(COALESCE(lib_id,'')) <> ''
                         GROUP BY lib_id,
                                  COALESCE(collection_root,''),
                                  COALESCE(folder,''),
                                  COALESCE(file_name,''),
                                  COALESCE(archive_entry,'')
                        HAVING COUNT(*) > 1
                  ) duplicate_groups
                """);
        long missingCovers = queryExecutor.queryForLong("""
                SELECT COUNT(*)
                  FROM books
                 WHERE COALESCE(deleted,0)=0
                   AND TRIM(COALESCE(cover_hash,'')) = ''
                """);

        String sql = """
                INSERT OR REPLACE INTO library_statistics
                (id, books_count, authors_count, series_count, genres_count,
                 languages_count, publishers_count, total_size_bytes,
                 duplicates_count, missing_covers_count, local_books_count,
                 remote_books_count, read_books_count, unread_books_count,
                 favorites_count, deleted_books_count, sources_count, last_updated)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        jt.update(sql, books, authors, series, genres, languages, publishers, totalSize,
                duplicates, missingCovers, local, remote, read, unread, favourites, deleted, sources);
        log.info("Statistics refreshed: books={}, local={}, remote={}, deleted={}, authors={}",
                books, local, remote, deleted, authors);
    }
}
