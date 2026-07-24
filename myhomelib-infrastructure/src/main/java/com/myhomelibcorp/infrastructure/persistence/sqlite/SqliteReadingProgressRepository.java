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

    private final RowMapper<ReadingProgressDto> rowMapper = (rs, rowNum) ->
            ReadingProgressDto.builder()
                    .bookId(rs.getString("book_id"))
                    .paragraphId(rs.getString("paragraph_id"))
                    .charOffset(rs.getInt("char_offset"))
                    .percent(rs.getDouble("percent"))
                    .updatedAt(LocalDateTime.parse(rs.getString("updated_at"), DATE_FORMATTER))
                    .build();

    @Override
    public void save(ReadingProgressDto progress) {
        String sql = """
                INSERT OR REPLACE INTO reading_progress (book_id, paragraph_id, char_offset, percent, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        getJdbcTemplate().update(sql,
                progress.getBookId(),
                progress.getParagraphId(),
                progress.getCharOffset(),
                progress.getPercent(),
                progress.getUpdatedAt() != null
                        ? progress.getUpdatedAt().format(DATE_FORMATTER)
                        : LocalDateTime.now().format(DATE_FORMATTER)
        );
        log.debug("Збережено прогрес читання для книги {}: параграф={}, зсув={}, %={}",
                progress.getBookId(), progress.getParagraphId(), progress.getCharOffset(), progress.getPercent());
    }

    @Override
    public Optional<ReadingProgressDto> findByBookId(String bookId) {
        String sql = "SELECT * FROM reading_progress WHERE book_id = ?";
        try {
            ReadingProgressDto dto = getJdbcTemplate().queryForObject(sql, rowMapper, bookId);
            return Optional.of(dto);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void deleteByBookId(String bookId) {
        getJdbcTemplate().update("DELETE FROM reading_progress WHERE book_id = ?", bookId);
    }
}