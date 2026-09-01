package com.myhomelibcorp.infrastructure.exchange;

import com.myhomelibcorp.application.port.out.exchange.ReadingHistoryPort;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteBusyRetryExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
public class SqliteReadingHistoryAdapter implements ReadingHistoryPort {
    private static final DateTimeFormatter HISTORY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final CollectionManager collections;
    private final SqliteBusyRetryExecutor busyRetry;

    public SqliteReadingHistoryAdapter(CollectionManager collections, SqliteBusyRetryExecutor busyRetry) {
        this.collections = collections;
        this.busyRetry = busyRetry;
    }

    @Override
    public List<Entry> recent(int limit) {
        int safe = Math.max(1, Math.min(limit, 5000));
        return collections.getCurrentJdbcTemplate().query("""
                SELECT rh.book_id, rh.last_opened_at
                FROM reading_history rh
                JOIN books b ON b.id = rh.book_id
                WHERE b.deleted = 0
                ORDER BY rh.last_opened_at DESC, rh.book_id ASC
                LIMIT ?
                """, (rs, n) -> new Entry(
                BookId.fromString(rs.getString("book_id")),
                parseDateTime(rs.getString("last_opened_at"))), safe);
    }

    @Override
    public long count() {
        Long value = collections.getCurrentJdbcTemplate().queryForObject("""
                SELECT COUNT(*)
                FROM reading_history rh
                JOIN books b ON b.id = rh.book_id
                WHERE b.deleted = 0
                """, Long.class);
        return value == null ? 0L : value;
    }

    @Override
    public void recordOpened(BookId bookId) {
        if (bookId == null) return;
        String now = LocalDateTime.now().format(HISTORY_TIMESTAMP);
        busyRetry.run("reading history write", () -> collections.getCurrentJdbcTemplate().update("""
                INSERT INTO reading_history(book_id, last_opened_at, open_count)
                VALUES (?, ?, 1)
                ON CONFLICT(book_id) DO UPDATE SET
                    last_opened_at = excluded.last_opened_at,
                    open_count = reading_history.open_count + 1
                """, bookId.asString(), now));
    }

    @Override
    public void clear() {
        busyRetry.run("reading history clear", () -> collections.getCurrentJdbcTemplate().update("DELETE FROM reading_history"));
    }

    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return LocalDateTime.MIN;
        String normalized = value.trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.MIN;
        }
    }
}
