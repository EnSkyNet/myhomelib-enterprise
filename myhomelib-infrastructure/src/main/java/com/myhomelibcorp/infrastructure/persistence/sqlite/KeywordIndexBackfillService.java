package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.KeywordIndexSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One-time, resumable backfill for the normalized keyword projection. */
@Component
@RequiredArgsConstructor
@Slf4j
public class KeywordIndexBackfillService {
    private static final String MARKER = "v71_keyword_index_nfkc_v1";
    private static final int BATCH_SIZE = 1000;

    private final CollectionManager collectionManager;

    public long backfillIfNeeded() {
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        if (jdbc == null) return 0L;
        String marker = jdbc.query("SELECT value FROM settings WHERE key=?",
                rs -> rs.next() ? rs.getString(1) : null, MARKER);
        if ("1".equals(marker)) return 0L;

        long processed = 0L;
        String afterId = "";
        while (true) {
            List<BookKeywordRow> rows = jdbc.query("""
                    SELECT id, keywords
                    FROM books
                    WHERE id > ?
                    ORDER BY id
                    LIMIT ?
                    """, (rs, rowNum) -> new BookKeywordRow(rs.getString(1), rs.getString(2)), afterId, BATCH_SIZE);
            if (rows.isEmpty()) break;

            Map<String, String> batch = new LinkedHashMap<>(rows.size());
            for (BookKeywordRow row : rows) batch.put(row.id(), row.keywords());
            KeywordIndexSupport.replaceForBooks(jdbc, batch);
            processed += rows.size();
            afterId = rows.get(rows.size() - 1).id();
        }

        KeywordIndexSupport.removeOrphanKeywords(jdbc);
        jdbc.update("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                MARKER, "1");
        log.info("Нормалізований keyword index побудовано для {} книг", processed);
        return processed;
    }

    private record BookKeywordRow(String id, String keywords) {}
}
