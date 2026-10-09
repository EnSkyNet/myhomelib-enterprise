package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.domain.model.book.Book;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Shared chunked loader for one-to-many book relations stored in SQLite join tables. */
final class BookRelationBatchLoader {
    private BookRelationBatchLoader() {
    }

    static <T> void load(
            JdbcTemplate jdbcTemplate,
            List<Book> books,
            String sqlTemplate,
            RowMapper<T> rowMapper,
            BiConsumer<Book, T> attach) {
        if (books.isEmpty()) return;

        List<String> bookIds = books.stream().map(book -> book.getId().asString()).toList();
        Map<String, List<T>> relationsByBook = new HashMap<>();
        SqliteInClauseSupport.forEachChunk(bookIds, part -> {
            String sql = sqlTemplate.formatted(SqliteInClauseSupport.placeholders(part.size()));
            jdbcTemplate.query(sql, rs -> {
                String bookId = rs.getString("book_id");
                T relation = rowMapper.mapRow(rs, 0);
                relationsByBook.computeIfAbsent(bookId, ignored -> new ArrayList<>()).add(relation);
            }, part.toArray());
        });

        for (Book book : books) {
            for (T relation : relationsByBook.getOrDefault(book.getId().asString(), List.of())) {
                attach.accept(book, relation);
            }
        }
    }
}
