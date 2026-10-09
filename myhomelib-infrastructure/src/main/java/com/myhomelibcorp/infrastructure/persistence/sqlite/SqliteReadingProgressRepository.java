package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteDateTimeCodec;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteInClauseSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteReadingProgressRepository implements ReadingProgressRepository {

    private final CollectionManager collectionManager;
    private final SqliteBusyRetryExecutor busyRetry;

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
                .updatedAt(SqliteDateTimeCodec.parse(rs.getString("updated_at")))
                .readingTimeSeconds(rs.getLong("reading_time_seconds"))
                .lastDevice(rs.getString("last_device"))
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
                 chapter_title, chapter_id, updated_at, reading_time_seconds, last_device)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        // Normalize again at the persistence boundary. Most callers already sanitize progress,
        // but sync/restore/plugins are not allowed to persist NaN/Infinity or negative positions
        // even if a malformed DTO reaches the repository directly.
        final int paragraphIndex = Math.max(0, progress.getParagraphIndex());
        final int charOffset = Math.max(0, progress.getCharOffset());
        final double percent = Double.isFinite(progress.getPercent())
                ? Math.max(0.0, Math.min(100.0, progress.getPercent()))
                : 0.0;
        final long readingTimeSeconds = Math.max(0L, progress.getReadingTimeSeconds());

        // Значення, які використовуються всередині retry-lambda, мають бути final/effectively final.
        final String anchorId = progress.getAnchorId() != null && !progress.getAnchorId().isEmpty()
                ? progress.getAnchorId()
                : String.valueOf(paragraphIndex);

        final String paragraphId = progress.getParagraphId() == null || progress.getParagraphId().isEmpty()
                ? anchorId
                : progress.getParagraphId();

        final String chapterTitle = progress.getChapterTitle() == null
                ? ""
                : progress.getChapterTitle();

        final String chapterId = progress.getChapterId() == null
                ? ""
                : progress.getChapterId();

        final String updatedAt = progress.getUpdatedAt() != null
                ? SqliteDateTimeCodec.format(progress.getUpdatedAt())
                : SqliteDateTimeCodec.format(LocalDateTime.now());

        final String lastDevice = progress.getLastDevice() == null || progress.getLastDevice().isBlank()
                ? "desktop"
                : progress.getLastDevice().trim();

        int summaryProgress = (int) Math.round(percent);
        busyRetry.run("reading progress save", () -> {
            JdbcTemplate jdbc = getJdbcTemplate();
            jdbc.update(sql,
                    progress.getBookId(),
                    anchorId,
                    paragraphIndex,
                    paragraphId,
                    charOffset,
                    percent,
                    chapterTitle,
                    chapterId,
                    updatedAt,
                    readingTimeSeconds,
                    lastDevice
            );
            // The main table and legacy progress filters read books.progress.
            jdbc.update("UPDATE books SET progress = ? WHERE id = ?", summaryProgress, progress.getBookId());
        });

        log.debug("Saved reading progress for book {}: anchor={}, paragraph_id={}, charOffset={}, %={}",
                progress.getBookId(), anchorId, paragraphId, charOffset, percent);
    }

    @Override
    public Optional<ReadingProgressDto> findByBookId(String bookId) {
        if (bookId == null || bookId.isBlank()) return Optional.empty();
        String sql = "SELECT * FROM reading_progress WHERE book_id = ? LIMIT 1";
        return getJdbcTemplate().query(sql, rowMapper, bookId).stream().findFirst();
    }

    @Override
    public Map<String, ReadingProgressDto> findByBookIds(Collection<String> bookIds) {
        Map<String, ReadingProgressDto> result = new LinkedHashMap<>();
        if (bookIds == null || bookIds.isEmpty()) return result;
        var ids = bookIds.stream().filter(java.util.Objects::nonNull).filter(id -> !id.isBlank()).distinct().toList();
        SqliteInClauseSupport.forEachChunk(ids, part -> {
            String sql = "SELECT * FROM reading_progress WHERE book_id IN (" + SqliteInClauseSupport.placeholders(part.size()) + ")";
            for (ReadingProgressDto value : getJdbcTemplate().query(sql, rowMapper, part.toArray())) result.put(value.getBookId(), value);
        });
        return result;
    }

    @Override
    public void deleteByBookId(String bookId) {
        busyRetry.run("reading progress delete", () -> getJdbcTemplate().update("DELETE FROM reading_progress WHERE book_id = ?", bookId));
        log.debug("Deleted reading progress for book: {}", bookId);
    }

}