package com.myhomelibcorp.infrastructure.persistence.mapper;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.*;
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

        // Створюємо BookFile
        BookFile file = new BookFile(
                rs.getString("file_name"),
                rs.getString("folder"),
                rs.getString("archive_entry"),
                rs.getLong("file_size"),
                null // collectionRoot – буде встановлено пізніше в ViewModel
        );

        // Створюємо BookMetadata
        BookMetadata metadata = BookMetadata.builder()
                .annotation(rs.getString("annotation"))
                .keywords(rs.getString("keywords"))
                .language(rs.getString("language") != null ? LanguageCode.of(rs.getString("language")) : LanguageCode.of("uk"))
                .isbn(rs.getString("isbn") != null ? Isbn.of(rs.getString("isbn")) : null)
                .review(rs.getString("review"))
                .rate(rs.getInt("rate"))
                .progress(rs.getInt("progress"))
                .build();

        LocalDateTime updateDate = parseDate(rs.getString("update_date"));
        LocalDateTime createdAt = parseDate(rs.getString("created_at"));

        return Book.builder()
                .id(id)
                .title(rs.getString("title"))
                .series(rs.getString("series"))
                .sequenceNumber(rs.getInt("sequence_number"))
                .metadata(metadata)
                .file(file)
                .updateDate(updateDate)
                .createdAt(createdAt)
                .deleted(rs.getInt("deleted") == 1)
                .local(rs.getInt("local") == 1)
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