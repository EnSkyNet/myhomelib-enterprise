package com.myhomelibcorp.infrastructure.exchange;

import com.myhomelibcorp.application.port.out.exchange.ReadingHistoryPort;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SqliteReadingHistoryAdapter implements ReadingHistoryPort {
    private final CollectionManager collections;
    public SqliteReadingHistoryAdapter(CollectionManager collections) { this.collections = collections; }

    @Override public List<BookId> recent(int limit) {
        int safe = Math.max(1, Math.min(limit, 5000));
        return collections.getCurrentJdbcTemplate().query("""
                SELECT b.id FROM books b
                LEFT JOIN reading_stats rs ON rs.book_id=b.id
                LEFT JOIN reading_progress rp ON rp.book_id=b.id
                WHERE COALESCE(rs.last_read_at, rp.updated_at) IS NOT NULL
                ORDER BY COALESCE(rs.last_read_at, rp.updated_at) DESC LIMIT ?
                """, (rs,n) -> BookId.fromString(rs.getString(1)), safe);
    }
}
