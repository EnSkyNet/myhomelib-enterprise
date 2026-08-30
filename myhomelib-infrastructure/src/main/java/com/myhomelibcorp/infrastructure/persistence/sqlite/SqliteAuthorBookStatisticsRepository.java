package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.dto.AuthorBookStatistics;
import com.myhomelibcorp.application.port.out.statistics.AuthorBookStatisticsPort;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "app.database.type", havingValue = "sqlite", matchIfMissing = true)
@RequiredArgsConstructor
public class SqliteAuthorBookStatisticsRepository implements AuthorBookStatisticsPort {
    private final CollectionManager collectionManager;

    @Override
    public AuthorBookStatistics load(AuthorId authorId) {
        if (authorId == null) return AuthorBookStatistics.empty();
        String id = authorId.asString();
        String sql = """
                SELECT
                  (SELECT COUNT(*)
                     FROM book_authors ba JOIN books b ON b.id=ba.book_id
                    WHERE ba.author_id=? AND b.deleted=0) AS books_count,
                  (SELECT COUNT(DISTINCT NULLIF(TRIM(b.series), ''))
                     FROM book_authors ba JOIN books b ON b.id=ba.book_id
                    WHERE ba.author_id=? AND b.deleted=0) AS series_count,
                  (SELECT COUNT(DISTINCT bg.genre_code)
                     FROM book_authors ba
                     JOIN books b ON b.id=ba.book_id
                     JOIN book_genres bg ON bg.book_id=b.id
                    WHERE ba.author_id=? AND b.deleted=0) AS genres_count
                """;
        return collectionManager.getCurrentJdbcTemplate().queryForObject(sql, (rs, rowNum) ->
                new AuthorBookStatistics(rs.getLong("books_count"), rs.getLong("series_count"), rs.getLong("genres_count")),
                id, id, id);
    }
}
