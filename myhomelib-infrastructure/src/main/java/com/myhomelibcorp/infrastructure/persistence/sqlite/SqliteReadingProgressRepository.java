package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
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
public class SqliteReadingProgressRepository implements ReadingProgressRepository {

    private final CollectionManager collectionManager;
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    private final RowMapper<ReadingProgressDto> rowMapper = (rs, rowNum) -> {
        ReadingProgressDto dto = ReadingProgressDto.builder()
                .bookId(rs.getString("book_id"))
                .anchorId(rs.getString("anchor_id"))
                .paragraphIndex(rs.getInt("paragraph_index"))
                .paragraphId(rs.getString("paragraph_id"))
                .charOffset(rs.getInt("char_offset"))
                .percent(rs.getDouble("percent"))
                .chapterTitle(rs.getString("chapter_title"))
                .chapterId(rs.getString("chapter_id"))
                .updatedAt(parseDate(rs.getString("updated_at")))
                .readingTimeSeconds(rs.getLong("reading_time_seconds"))
                .build();

        // Якщо anchor_id порожній, використовуємо paragraph_id як fallback
        if (dto.getAnchorId() == null || dto.getAnchorId().isEmpty()) {
            dto.setAnchorId(dto.getParagraphId());
        }

        return dto;
    };

    @Override
    public void save(ReadingProgressDto progress) {
        // ===== ВИПРАВЛЕНО: ЗАВЖДИ ПЕРЕДАЄМО ЗНАЧЕННЯ ДЛЯ ВСІХ ПОЛІВ =====
        String sql = """
                INSERT OR REPLACE INTO reading_progress 
                (book_id, anchor_id, paragraph_index, paragraph_id, char_offset, percent, 
                 chapter_title, chapter_id, updated_at, reading_time_seconds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        // Отримуємо anchorId
        String anchorId = progress.getAnchorId() != null && !progress.getAnchorId().isEmpty()
                ? progress.getAnchorId()
                : String.valueOf(progress.getParagraphIndex());

        // ===== ВИПРАВЛЕНО: ЗАВЖДИ ВСТАНОВЛЮЄМО paragraph_id =====
        // Використовуємо anchorId як paragraph_id, якщо він не встановлений
        String paragraphId = progress.getParagraphId();
        if (paragraphId == null || paragraphId.isEmpty()) {
            paragraphId = anchorId;
        }

        // ===== ВИПРАВЛЕНО: ЗАВЖДИ ВСТАНОВЛЮЄМО chapter_title =====
        String chapterTitle = progress.getChapterTitle();
        if (chapterTitle == null) {
            chapterTitle = "";
        }

        // ===== ВИПРАВЛЕНО: ЗАВЖДИ ВСТАНОВЛЮЄМО chapter_id =====
        String chapterId = progress.getChapterId();
        if (chapterId == null) {
            chapterId = "";
        }

        String updatedAt = progress.getUpdatedAt() != null
                ? progress.getUpdatedAt().format(DATE_FORMATTER)
                : LocalDateTime.now().format(DATE_FORMATTER);

        getJdbcTemplate().update(sql,
                progress.getBookId(),
                anchorId,
                progress.getParagraphIndex(),
                paragraphId,           // <-- ТЕПЕР ЗАВЖДИ НЕ NULL
                progress.getCharOffset(),
                progress.getPercent(),
                chapterTitle,          // <-- ТЕПЕР ЗАВЖДИ НЕ NULL
                chapterId,             // <-- ТЕПЕР ЗАВЖДИ НЕ NULL
                updatedAt,
                progress.getReadingTimeSeconds()
        );

        log.debug("Saved reading progress for book {}: anchor={}, paragraph_id={}, charOffset={}, %={}",
                progress.getBookId(), anchorId, paragraphId, progress.getCharOffset(), progress.getPercent());
    }

    @Override
    public Optional<ReadingProgressDto> findByBookId(String bookId) {
        String sql = "SELECT * FROM reading_progress WHERE book_id = ?";
        try {
            ReadingProgressDto dto = getJdbcTemplate().queryForObject(sql, rowMapper, bookId);
            return Optional.of(dto);
        } catch (Exception e) {
            log.warn("No reading progress found for book {}: {}", bookId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void deleteByBookId(String bookId) {
        getJdbcTemplate().update("DELETE FROM reading_progress WHERE book_id = ?", bookId);
        log.debug("Deleted reading progress for book: {}", bookId);
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