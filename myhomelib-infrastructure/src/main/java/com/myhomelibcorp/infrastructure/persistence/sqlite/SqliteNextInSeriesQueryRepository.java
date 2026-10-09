package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.dto.NextInSeriesItemDto;
import com.myhomelibcorp.application.port.out.repository.NextInSeriesQueryRepository;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class SqliteNextInSeriesQueryRepository implements NextInSeriesQueryRepository {
    private final CollectionManager collectionManager;
    private JdbcTemplate jdbc() { return collectionManager.getCurrentJdbcTemplate(); }

    @Override
    public List<NextInSeriesItemDto> findNextInSeries(int limit) {
        int safe = Math.max(1, Math.min(50, limit));
        String sql = """
                WITH completed AS (
                    SELECT LOWER(TRIM(b.series)) AS series_key, MAX(b.sequence_number) AS last_completed
                      FROM books b
                      LEFT JOIN reading_progress rp ON rp.book_id=b.id
                     WHERE COALESCE(b.deleted,0)=0
                       AND b.series IS NOT NULL AND TRIM(b.series)<>''
                       AND b.sequence_number IS NOT NULL
                       AND MAX(COALESCE(b.progress,0),COALESCE(rp.percent,0))>=100.0
                     GROUP BY LOWER(TRIM(b.series))
                ),
                blocked AS (
                    SELECT DISTINCT LOWER(TRIM(b.series)) AS series_key
                      FROM books b
                      LEFT JOIN reading_progress rp ON rp.book_id=b.id
                     WHERE COALESCE(b.deleted,0)=0
                       AND b.series IS NOT NULL AND TRIM(b.series)<>''
                       AND MAX(COALESCE(b.progress,0),COALESCE(rp.percent,0))>0.0
                       AND MAX(COALESCE(b.progress,0),COALESCE(rp.percent,0))<100.0
                ),
                candidate_seq AS (
                    SELECT c.series_key, MIN(b.sequence_number) AS candidate_sequence
                      FROM completed c
                      JOIN books b ON LOWER(TRIM(b.series))=c.series_key
                      LEFT JOIN reading_progress rp ON rp.book_id=b.id
                     WHERE COALESCE(b.deleted,0)=0
                       AND b.missing_since IS NULL
                       AND b.sequence_number IS NOT NULL
                       AND b.sequence_number>c.last_completed
                       AND MAX(COALESCE(b.progress,0),COALESCE(rp.percent,0))<=0.0
                       AND NOT EXISTS (SELECT 1 FROM blocked x WHERE x.series_key=c.series_key)
                     GROUP BY c.series_key
                ),
                unique_candidate AS (
                    SELECT cs.series_key, cs.candidate_sequence
                      FROM candidate_seq cs
                      JOIN books b ON LOWER(TRIM(b.series))=cs.series_key AND b.sequence_number=cs.candidate_sequence
                     WHERE COALESCE(b.deleted,0)=0 AND b.missing_since IS NULL
                     GROUP BY cs.series_key,cs.candidate_sequence
                    HAVING COUNT(*)=1
                )
                SELECT b.id,b.title,b.series,b.sequence_number
                  FROM unique_candidate u
                  JOIN books b ON LOWER(TRIM(b.series))=u.series_key AND b.sequence_number=u.candidate_sequence
                 WHERE COALESCE(b.deleted,0)=0 AND b.missing_since IS NULL
                 ORDER BY LOWER(TRIM(b.series)), b.sequence_number, b.id
                 LIMIT ?
                """;
        return jdbc().query(sql, (rs, row) -> new NextInSeriesItemDto(
                rs.getString("id"), rs.getString("title"), rs.getString("series"), rs.getInt("sequence_number")), safe);
    }
}
