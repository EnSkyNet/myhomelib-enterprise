package com.myhomelibcorp.infrastructure.persistence.mapper;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class BookRowMapper implements RowMapper<Book> {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public Book mapRow(ResultSet rs, int rowNum) throws SQLException {
        BookId id = BookId.fromString(rs.getString("id"));

        LocalDateTime updateDate = parseDate(rs.getString("update_date"));
        LocalDateTime createdAt = parseDate(rs.getString("created_at"));

        return Book.builder()
                .id(id)
                .title(rs.getString("title"))
                .series(rs.getString("series"))
                .sequenceNumber(rs.getInt("sequence_number"))
                .language(rs.getString("language"))
                .fileName(rs.getString("file_name"))
                .folder(rs.getString("folder"))
                .archiveEntry(rs.getString("archive_entry"))
                .fileSize(rs.getLong("file_size"))
                .keywords(rs.getString("keywords"))
                .annotation(rs.getString("annotation"))
                .rate(rs.getInt("rate"))
                .progress(rs.getInt("progress"))
                .updateDate(updateDate)
                .deleted(rs.getInt("deleted") == 1)
                .local(rs.getInt("local") == 1)
                .review(rs.getString("review"))
                .createdAt(createdAt)
                .build();
    }

    private LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;
        try {
            return LocalDateTime.parse(dateStr, DATE_FORMATTER);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(dateStr);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}