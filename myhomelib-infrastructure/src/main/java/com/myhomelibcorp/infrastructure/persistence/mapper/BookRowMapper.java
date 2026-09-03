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
import java.time.format.DateTimeParseException;

@Component
public class BookRowMapper implements RowMapper<Book> {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public Book mapRow(ResultSet rs, int rowNum) throws SQLException {
        BookId id = BookId.fromString(rs.getString("id"));

        // Безпечне читання всіх полів з перевіркою на null
        String title = rs.getString("title");
        String series = rs.getString("series");
        int sequenceNumber = rs.getInt("sequence_number");

        // File fields
        String fileName = rs.getString("file_name");
        String folder = rs.getString("folder");
        String archiveEntry = rs.getString("archive_entry");
        long fileSize = rs.getLong("file_size");
        String collectionRoot = rs.getString("collection_root");
        if (collectionRoot == null) collectionRoot = "";

        BookFile file = new BookFile(
                fileName != null ? fileName : "",
                folder != null ? folder : "",
                archiveEntry != null ? archiveEntry : "",
                fileSize,
                collectionRoot
        );

        // Metadata
        String annotation = rs.getString("annotation");
        String keywords = rs.getString("keywords");
        String language = rs.getString("language");
        String isbn = rs.getString("isbn");
        String review = rs.getString("review");
        Integer year = rs.getInt("year");
        if (rs.wasNull()) year = null;
        String publisher = rs.getString("publisher");
        String libId = rs.getString("lib_id");
        int libraryRate = rs.getInt("library_rate");
        String translators = rs.getString("translators");
        String city = rs.getString("city");
        String sourceUrl = rs.getString("source_url");
        int rate = rs.getInt("rate");
        int progress = rs.getInt("progress");

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

        LocalDateTime updateDate = parseDate(rs.getString("update_date"));
        LocalDateTime createdAt = parseDate(rs.getString("created_at"));

        boolean deleted = rs.getInt("deleted") == 1;
        boolean local = rs.getInt("local") == 1;
        LocalDateTime missingSince = parseDate(rs.getString("missing_since"));

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
                .missingSince(missingSince)
                .build();
    }

    private Isbn parseIsbn(String value) {
        return Isbn.tryParse(value).orElse(null);
    }


    private LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;
        try {
            return LocalDateTime.parse(dateStr, DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(dateStr);
            } catch (DateTimeParseException ex) {
                return null;
            }
        }
    }
}