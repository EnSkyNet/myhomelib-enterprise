package com.myhomelibcorp.infrastructure.persistence.mapper;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.*;
import com.myhomelibcorp.domain.service.LanguageResolver;
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

        // Безпечне читання всіх полів з перевіркою на null
        String title = safeGetString(rs, "title");
        String series = safeGetString(rs, "series");
        int sequenceNumber = safeGetInt(rs, "sequence_number");

        // File fields
        String fileName = safeGetString(rs, "file_name");
        String folder = safeGetString(rs, "folder");
        String archiveEntry = safeGetString(rs, "archive_entry");
        long fileSize = safeGetLong(rs, "file_size");
        String collectionRoot = safeGetString(rs, "collection_root");
        if (collectionRoot == null) collectionRoot = "";

        BookFile file = new BookFile(
                fileName != null ? fileName : "",
                folder != null ? folder : "",
                archiveEntry != null ? archiveEntry : "",
                fileSize,
                collectionRoot
        );

        // Metadata
        String annotation = safeGetString(rs, "annotation");
        String keywords = safeGetString(rs, "keywords");
        String language = safeGetString(rs, "language");
        String isbn = safeGetString(rs, "isbn");
        String review = safeGetString(rs, "review");
        Integer year = safeGetNullableInt(rs, "year");
        String publisher = safeGetString(rs, "publisher");
        String libId = safeGetString(rs, "lib_id");
        int libraryRate = safeGetInt(rs, "library_rate");
        String translators = safeGetString(rs, "translators");
        String city = safeGetString(rs, "city");
        String sourceUrl = safeGetString(rs, "source_url");
        int rate = safeGetInt(rs, "rate");
        int progress = safeGetInt(rs, "progress");

        BookMetadata metadata = BookMetadata.builder()
                .annotation(annotation != null ? annotation : "")
                .keywords(keywords != null ? keywords : "")
                .language(LanguageResolver.resolve(language))
                .isbn(parseIsbn(isbn))
                .review(review != null ? review : "")
                .year(year)
                .publisher(publisher != null ? publisher : "")
                .libId(libId != null ? libId : "")
                .libraryRate(libraryRate)
                .translators(translators != null ? translators : "")
                .city(city != null ? city : "")
                .sourceUrl(sourceUrl != null ? sourceUrl : "")
                .rate(rate)
                .progress(progress)
                .build();

        LocalDateTime updateDate = parseDate(safeGetString(rs, "update_date"));
        LocalDateTime createdAt = parseDate(safeGetString(rs, "created_at"));

        boolean deleted = safeGetInt(rs, "deleted") == 1;
        boolean local = safeGetInt(rs, "local") == 1;

        return Book.builder()
                .id(id)
                .title(title != null ? title : "")
                .series(series != null ? series : "")
                .sequenceNumber(sequenceNumber)
                .metadata(metadata)
                .file(file)
                .updateDate(updateDate != null ? updateDate : LocalDateTime.now())
                .createdAt(createdAt != null ? createdAt : LocalDateTime.now())
                .deleted(deleted)
                .local(local)
                .build();
    }

    private Isbn parseIsbn(String value) {
        return Isbn.tryParse(value).orElse(null);
    }

    // ===== Безпечні геттери =====
    private String safeGetString(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    private int safeGetInt(ResultSet rs, String column) {
        try {
            return rs.getInt(column);
        } catch (SQLException e) {
            return 0;
        }
    }

    private Integer safeGetNullableInt(ResultSet rs, String column) {
        try {
            int value = rs.getInt(column);
            return rs.wasNull() ? null : value;
        } catch (SQLException e) {
            return null;
        }
    }

    private long safeGetLong(ResultSet rs, String column) {
        try {
            return rs.getLong(column);
        } catch (SQLException e) {
            return 0L;
        }
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