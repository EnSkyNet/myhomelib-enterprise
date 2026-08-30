package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.dto.ReadingSessionRecord;
import com.myhomelibcorp.application.dto.ReadingStatisticsDto;
import com.myhomelibcorp.application.port.out.statistics.ReadingStatisticsPort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteDateTimeCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteReadingStatisticsRepository implements ReadingStatisticsPort {

    private final CollectionManager collectionManager;
    private final QueryExecutor queryExecutor;

    private final RowMapper<ReadingStatisticsDto> rowMapper = (rs, rowNum) -> ReadingStatisticsDto.builder()
            .bookId(rs.getString("book_id"))
            .firstReadAt(SqliteDateTimeCodec.parse(rs.getString("first_read_at")))
            .lastReadAt(SqliteDateTimeCodec.parse(rs.getString("last_read_at")))
            .totalReadingSeconds(rs.getLong("total_reading_seconds"))
            .readingSessions(rs.getInt("reading_sessions"))
            .startPercent(rs.getInt("start_percent"))
            .endPercent(rs.getInt("end_percent"))
            .currentPercent(rs.getInt("current_percent"))
            .completedAt(SqliteDateTimeCodec.parse(rs.getString("completed_at")))
            .build();

    @Override
    public void recordSession(ReadingSessionRecord session) {
        if (session == null || session.bookId() == null || session.bookId().isBlank()) return;

        LocalDateTime startedAt = session.startedAt() != null ? session.startedAt() : LocalDateTime.now();
        LocalDateTime endedAt = session.endedAt() != null ? session.endedAt() : LocalDateTime.now();
        long duration = Math.max(0L, session.durationSeconds());
        int startPercent = clampPercent(session.startPercent());
        int currentPercent = clampPercent(session.currentPercent());
        String completedAt = currentPercent >= 100 ? SqliteDateTimeCodec.format(endedAt) : null;

        jdbc().update("""
                INSERT INTO reading_stats
                    (book_id,first_read_at,last_read_at,total_reading_seconds,reading_sessions,
                     start_percent,end_percent,current_percent,completed_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(book_id) DO UPDATE SET
                    first_read_at=CASE
                        WHEN excluded.first_read_at < reading_stats.first_read_at THEN excluded.first_read_at
                        ELSE reading_stats.first_read_at END,
                    last_read_at=CASE
                        WHEN excluded.last_read_at > reading_stats.last_read_at THEN excluded.last_read_at
                        ELSE reading_stats.last_read_at END,
                    total_reading_seconds=MAX(0,reading_stats.total_reading_seconds) + excluded.total_reading_seconds,
                    reading_sessions=MAX(0,reading_stats.reading_sessions) + 1,
                    start_percent=reading_stats.start_percent,
                    end_percent=MAX(reading_stats.end_percent,excluded.end_percent),
                    current_percent=CASE
                        WHEN excluded.last_read_at >= reading_stats.last_read_at THEN excluded.current_percent
                        ELSE reading_stats.current_percent END,
                    completed_at=CASE
                        WHEN reading_stats.completed_at IS NULL THEN excluded.completed_at
                        WHEN excluded.completed_at IS NULL THEN reading_stats.completed_at
                        WHEN excluded.completed_at < reading_stats.completed_at THEN excluded.completed_at
                        ELSE reading_stats.completed_at END
                """,
                session.bookId(),
                SqliteDateTimeCodec.format(startedAt),
                SqliteDateTimeCodec.format(endedAt),
                duration,
                1,
                startPercent,
                Math.max(startPercent, currentPercent),
                currentPercent,
                completedAt);
    }

    @Override
    public Optional<ReadingStatisticsDto> findByBookId(String bookId) {
        if (bookId == null || bookId.isBlank()) return Optional.empty();
        return queryExecutor.query(
                "SELECT * FROM reading_stats WHERE book_id=? LIMIT 1", rowMapper, bookId)
                .stream().findFirst();
    }

    @Override
    public void deleteByBookId(String bookId) {
        if (bookId == null || bookId.isBlank()) return;
        jdbc().update("DELETE FROM reading_stats WHERE book_id=?", bookId);
    }

    private JdbcTemplate jdbc() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
