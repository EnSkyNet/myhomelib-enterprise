package com.myhomelibcorp.infrastructure.bulk;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BulkUpdateService {

    private final CollectionManager collectionManager;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    public void bulkUpdateGenre(List<String> bookIds, String newGenreCode) {
        if (bookIds == null || bookIds.isEmpty()) return;
        int batchSize = 500;
        for (int i = 0; i < bookIds.size(); i += batchSize) {
            List<String> batch = bookIds.subList(i, Math.min(i + batchSize, bookIds.size()));
            String placeholders = batch.stream().map(id -> "?").collect(Collectors.joining(","));
            String sql = "UPDATE book_genres SET genre_code = ? WHERE book_id IN (" + placeholders + ")";
            Object[] params = new Object[batch.size() + 1];
            params[0] = newGenreCode;
            System.arraycopy(batch.toArray(), 0, params, 1, batch.size());
            getJdbcTemplate().update(sql, params);
        }
        log.info("Bulk updated genre for {} books", bookIds.size());
    }

    public void bulkDeleteBooks(List<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) return;
        int batchSize = 500;
        for (int i = 0; i < bookIds.size(); i += batchSize) {
            List<String> batch = bookIds.subList(i, Math.min(i + batchSize, bookIds.size()));
            String placeholders = batch.stream().map(id -> "?").collect(Collectors.joining(","));
            String sql = "DELETE FROM books WHERE id IN (" + placeholders + ")";
            getJdbcTemplate().update(sql, batch.toArray());
        }
        log.info("Bulk deleted {} books", bookIds.size());
    }
}