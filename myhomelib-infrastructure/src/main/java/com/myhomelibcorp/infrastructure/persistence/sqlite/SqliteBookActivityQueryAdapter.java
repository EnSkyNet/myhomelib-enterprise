package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.activity.BookActivitySummary;
import com.myhomelibcorp.application.port.out.activity.BookActivityQueryPort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteInClauseSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class SqliteBookActivityQueryAdapter implements BookActivityQueryPort {
    private final CollectionManager collectionManager;

    @Override
    public Map<String, BookActivitySummary> summarize(Collection<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) return Map.of();
        List<String> ids = bookIds.stream().filter(java.util.Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().toList();
        if (ids.isEmpty()) return Map.of();

        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        LinkedHashMap<String, MutableCounts> counts = new LinkedHashMap<>();
        for (String id : ids) counts.put(id, new MutableCounts());
        SqliteInClauseSupport.forEachChunk(ids, part -> loadAnnotations(jdbc, part, counts));
        SqliteInClauseSupport.forEachChunk(ids, part -> loadBookmarks(jdbc, part, counts));

        LinkedHashMap<String, BookActivitySummary> result = new LinkedHashMap<>();
        counts.forEach((bookId, value) -> result.put(bookId,
                new BookActivitySummary(bookId, value.notes, value.highlights, value.bookmarks)));
        return Map.copyOf(result);
    }

    private static void loadAnnotations(JdbcTemplate jdbc, List<String> ids, Map<String, MutableCounts> counts) {
        String sql = "SELECT book_id, "
                + "SUM(CASE WHEN annotation_type='NOTE' THEN 1 ELSE 0 END) AS notes, "
                + "SUM(CASE WHEN annotation_type='HIGHLIGHT' THEN 1 ELSE 0 END) AS highlights "
                + "FROM annotations WHERE book_id IN (" + SqliteInClauseSupport.placeholders(ids.size()) + ") GROUP BY book_id";
        jdbc.query(sql, rs -> {
            MutableCounts c = counts.get(rs.getString("book_id"));
            if (c != null) {
                c.notes = rs.getInt("notes");
                c.highlights = rs.getInt("highlights");
            }
        }, ids.toArray());
    }

    private static void loadBookmarks(JdbcTemplate jdbc, List<String> ids, Map<String, MutableCounts> counts) {
        String sql = "SELECT book_id, COUNT(*) AS bookmarks FROM bookmarks WHERE book_id IN ("
                + SqliteInClauseSupport.placeholders(ids.size()) + ") GROUP BY book_id";
        jdbc.query(sql, rs -> {
            MutableCounts c = counts.get(rs.getString("book_id"));
            if (c != null) c.bookmarks = rs.getInt("bookmarks");
        }, ids.toArray());
    }

    private static class MutableCounts {
        private int notes;
        private int highlights;
        private int bookmarks;
    }
}
