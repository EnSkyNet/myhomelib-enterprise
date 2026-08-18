package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.dto.ReadingStatisticsDto;
import com.myhomelibcorp.application.port.out.statistics.ReadingStatisticsPort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteReadingStatisticsRepository implements ReadingStatisticsPort {

    private final CollectionManager collectionManager;
    private final QueryExecutor queryExecutor;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final RowMapper<ReadingStatisticsDto> rowMapper = (rs, rowNum) -> {
        String bookId = rs.getString("book_id");
        LocalDateTime firstReadAt = parseDate(rs.getString("first_read_at"));
        LocalDateTime lastReadAt = parseDate(rs.getString("last_read_at"));
        long totalReadingSeconds = rs.getLong("total_reading_seconds");
        int readingSessions = rs.getInt("reading_sessions");
        int startPercent = rs.getInt("start_percent");
        int endPercent = rs.getInt("end_percent");
        int currentPercent = rs.getInt("current_percent");
        LocalDateTime completedAt = parseDate(rs.getString("completed_at"));

        return ReadingStatisticsDto.builder()
                .bookId(bookId)
                .firstReadAt(firstReadAt)
                .lastReadAt(lastReadAt)
                .totalReadingSeconds(totalReadingSeconds)
                .readingSessions(readingSessions)
                .startPercent(startPercent)
                .endPercent(endPercent)
                .currentPercent(currentPercent)
                .completedAt(completedAt)
                .build();
    };

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public void save(ReadingStatisticsDto stats) {
        if (stats == null || stats.getBookId() == null) {
            log.warn("Cannot save null stats or stats with null bookId");
            return;
        }

        String sql = """
                INSERT OR REPLACE INTO reading_stats
                (book_id, first_read_at, last_read_at, total_reading_seconds,
                 reading_sessions, start_percent, end_percent, current_percent, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        String firstReadAt = stats.getFirstReadAt() != null
                ? stats.getFirstReadAt().format(DATE_FORMATTER)
                : LocalDateTime.now().format(DATE_FORMATTER);

        String lastReadAt = stats.getLastReadAt() != null
                ? stats.getLastReadAt().format(DATE_FORMATTER)
                : LocalDateTime.now().format(DATE_FORMATTER);

        String completedAt = stats.getCompletedAt() != null
                ? stats.getCompletedAt().format(DATE_FORMATTER)
                : null;

        getJdbcTemplate().update(sql,
                stats.getBookId(),
                firstReadAt,
                lastReadAt,
                stats.getTotalReadingSeconds(),
                stats.getReadingSessions(),
                stats.getStartPercent(),
                stats.getEndPercent(),
                stats.getCurrentPercent(),
                completedAt
        );

        log.debug("Saved reading stats for book: {}", stats.getBookId());
    }

    @Override
    public Optional<ReadingStatisticsDto> findByBookId(String bookId) {
        if (bookId == null || bookId.isEmpty()) {
            return Optional.empty();
        }

        String sql = "SELECT * FROM reading_stats WHERE book_id = ?";
        try {
            ReadingStatisticsDto stats = queryExecutor.queryForObject(sql, rowMapper, bookId);
            return Optional.of(stats);
        } catch (Exception e) {
            log.trace("No reading stats found for book: {}", bookId);
            return Optional.empty();
        }
    }

    @Override
    public void deleteByBookId(String bookId) {
        if (bookId == null || bookId.isEmpty()) {
            return;
        }
        String sql = "DELETE FROM reading_stats WHERE book_id = ?";
        getJdbcTemplate().update(sql, bookId);
        log.debug("Deleted reading stats for book: {}", bookId);
    }

    @Override
    public void updateProgress(String bookId, int percent) {
        if (bookId == null || bookId.isEmpty()) {
            return;
        }

        String sql = """
                UPDATE reading_stats
                SET current_percent = ?, last_read_at = ?
                WHERE book_id = ?
                """;

        String now = LocalDateTime.now().format(DATE_FORMATTER);
        int updated = getJdbcTemplate().update(sql, percent, now, bookId);

        if (updated == 0) {
            ReadingStatisticsDto newStats = ReadingStatisticsDto.builder()
                    .bookId(bookId)
                    .firstReadAt(LocalDateTime.now())
                    .lastReadAt(LocalDateTime.now())
                    .currentPercent(percent)
                    .readingSessions(0)
                    .startPercent(0)
                    .endPercent(0)
                    .build();
            save(newStats);
        }
    }

    private LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateStr, DATE_FORMATTER);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(dateStr);
            } catch (Exception ex) {
                log.warn("Failed to parse date: {}", dateStr);
                return null;
            }
        }
    }
}