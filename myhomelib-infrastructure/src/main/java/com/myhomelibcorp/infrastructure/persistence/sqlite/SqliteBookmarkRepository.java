package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.BookmarkRepository;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteBookmarkRepository implements BookmarkRepository {

    private final CollectionManager collectionManager;
    private final QueryExecutor queryExecutor;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final RowMapper<Bookmark> rowMapper = (rs, rowNum) -> {
        String id = rs.getString("id");
        String bookId = rs.getString("book_id");
        String paragraphId = rs.getString("paragraph_id");
        int charOffset = rs.getInt("char_offset");
        double position = rs.getDouble("position");
        String chapterTitle = rs.getString("chapter_title");
        String context = rs.getString("context");
        LocalDateTime createdAt = LocalDateTime.parse(rs.getString("created_at"), DATE_FORMATTER);

        return Bookmark.builder()
                .id(id)
                .bookId(bookId)
                .paragraphId(paragraphId)
                .charOffset(charOffset)
                .position(position)
                .chapterTitle(chapterTitle)
                .context(context)
                .createdAt(createdAt)
                .build();
    };

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public List<Bookmark> findByBookId(String bookId) {
        String sql = "SELECT * FROM bookmarks WHERE book_id = ? ORDER BY created_at DESC";
        return queryExecutor.query(sql, rowMapper, bookId);
    }


    @Override
    public Bookmark save(Bookmark bookmark) {
        String sql = """
                INSERT OR REPLACE INTO bookmarks
                (id, book_id, paragraph_id, char_offset, position, chapter_title, context, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        String createdAt = bookmark.getCreatedAt() != null
                ? bookmark.getCreatedAt().format(DATE_FORMATTER)
                : LocalDateTime.now().format(DATE_FORMATTER);

        getJdbcTemplate().update(sql,
                bookmark.getId(),
                bookmark.getBookId(),
                bookmark.getParagraphId(),
                bookmark.getCharOffset(),
                bookmark.getPosition(),
                bookmark.getChapterTitle(),
                bookmark.getContext(),
                createdAt
        );

        log.debug("Bookmark saved: {} for book {}", bookmark.getId(), bookmark.getBookId());
        return bookmark;
    }

    @Override
    public void deleteById(String id) {
        String sql = "DELETE FROM bookmarks WHERE id = ?";
        getJdbcTemplate().update(sql, id);
        log.debug("Bookmark deleted: {}", id);
    }


    @Override
    public int countByBookId(String bookId) {
        String sql = "SELECT COUNT(*) FROM bookmarks WHERE book_id = ?";
        return getJdbcTemplate().queryForObject(sql, Integer.class, bookId);
    }
}