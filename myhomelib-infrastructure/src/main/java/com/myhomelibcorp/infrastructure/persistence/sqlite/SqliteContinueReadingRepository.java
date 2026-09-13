package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.dto.ContinueReadingItemDto;
import com.myhomelibcorp.application.port.out.repository.ContinueReadingRepository;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteDateTimeCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** SQLite projection used by both desktop home and the Web Library Continue Reading page. */
@Repository
@RequiredArgsConstructor
public class SqliteContinueReadingRepository implements ContinueReadingRepository {
    private final CollectionManager collectionManager;

    @Override
    public List<ContinueReadingItemDto> findActive(int limit) {
        int safeLimit = Math.max(1, Math.min(50, limit));
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        String sql = """
                SELECT b.id AS book_id,
                       COALESCE(NULLIF(TRIM(b.title), ''), 'Без назви') AS title,
                       COALESCE((
                           SELECT group_concat(TRIM(COALESCE(a.last_name,'') || ' ' || COALESCE(a.first_name,'') || ' ' || COALESCE(a.middle_name,'')), ', ')
                             FROM book_authors ba
                             JOIN authors a ON a.id = ba.author_id
                            WHERE ba.book_id = b.id
                       ), '') AS authors,
                       rp.percent,
                       COALESCE(rp.chapter_title, '') AS chapter_title,
                       rp.updated_at,
                       COALESCE(NULLIF(TRIM(rp.last_device), ''), 'desktop') AS last_device
                  FROM reading_progress rp
                  JOIN books b ON b.id = rp.book_id
                 WHERE b.deleted = 0
                   AND COALESCE(rp.percent, 0) > 0
                   AND COALESCE(rp.percent, 0) < 100
                 ORDER BY rp.updated_at DESC, b.id
                 LIMIT ?
                """;
        return jdbc.query(sql, (rs, row) -> new ContinueReadingItemDto(
                rs.getString("book_id"),
                rs.getString("title"),
                rs.getString("authors"),
                rs.getDouble("percent"),
                rs.getString("chapter_title"),
                SqliteDateTimeCodec.parse(rs.getString("updated_at")),
                rs.getString("last_device")
        ), safeLimit);
    }
}
