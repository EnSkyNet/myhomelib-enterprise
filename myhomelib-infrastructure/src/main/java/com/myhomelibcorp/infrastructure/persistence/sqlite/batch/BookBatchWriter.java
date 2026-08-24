package com.myhomelibcorp.infrastructure.persistence.sqlite.batch;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.query.BookQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookBatchWriter {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final CollectionManager collectionManager;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    public void batchInsert(List<Book> books) {
        if (books == null || books.isEmpty()) return;

        JdbcTemplate jdbcTemplate = getJdbcTemplate();
        List<Object[]> batchArgs = new ArrayList<>(books.size());
        for (Book book : books) {
            Object[] args = new Object[27];
            int idx = 0;
            args[idx++] = book.getId().asString();
            args[idx++] = book.getTitle() != null ? book.getTitle() : "";
            args[idx++] = book.getSeries();
            args[idx++] = book.getSequenceNumber() != null ? book.getSequenceNumber() : 0;
            args[idx++] = book.getFileName() != null ? book.getFileName() : "";
            args[idx++] = book.getFolder() != null ? book.getFolder() : "";
            args[idx++] = book.getArchiveEntry() != null ? book.getArchiveEntry() : "";
            args[idx++] = book.getLanguage() != null ? book.getLanguage().toString() : null;
            args[idx++] = book.getFileSize();
            args[idx++] = book.getKeywords() != null ? book.getKeywords() : "";
            args[idx++] = book.getAnnotation() != null ? book.getAnnotation() : "";
            args[idx++] = book.getRate();
            args[idx++] = book.getProgress();
            String formattedDate = book.getUpdateDate() != null
                    ? book.getUpdateDate().format(DATE_FORMATTER)
                    : null;
            args[idx++] = formattedDate;
            args[idx++] = book.getIsbn() != null ? book.getIsbn().toString() : null;
            args[idx++] = book.isDeleted() ? 1 : 0;
            args[idx++] = book.isLocal() ? 1 : 0;
            args[idx++] = book.getReview() != null ? book.getReview() : "";
            String formattedCreated = book.getCreatedAt() != null
                    ? book.getCreatedAt().format(DATE_FORMATTER)
                    : LocalDateTime.now().format(DATE_FORMATTER);
            args[idx++] = formattedCreated;
            args[idx++] = book.getCollectionRoot() != null ? book.getCollectionRoot() : "";
            args[idx++] = book.getYear();
            args[idx++] = book.getPublisher() != null ? book.getPublisher() : "";
            args[idx++] = book.getLibId() != null ? book.getLibId() : "";
            args[idx++] = book.getLibraryRate();
            args[idx++] = book.getTranslators() != null ? book.getTranslators() : "";
            args[idx++] = book.getCity() != null ? book.getCity() : "";
            args[idx++] = book.getSourceUrl() != null ? book.getSourceUrl() : "";
            batchArgs.add(args);
        }

        jdbcTemplate.batchUpdate(BookQueries.INSERT_OR_UPDATE_BOOK, batchArgs);
        log.debug("Batch вставлено {} книг", books.size());
    }

    public void batchUpdate(List<Book> books) {
        if (books == null || books.isEmpty()) return;

        JdbcTemplate jdbcTemplate = getJdbcTemplate();
        List<Object[]> batchArgs = new ArrayList<>(books.size());
        for (Book book : books) {
            Object[] args = new Object[28];
            int idx = 0;
            args[idx++] = book.getTitle() != null ? book.getTitle() : "";
            args[idx++] = book.getSeries();
            args[idx++] = book.getSequenceNumber() != null ? book.getSequenceNumber() : 0;
            args[idx++] = book.getFileName() != null ? book.getFileName() : "";
            args[idx++] = book.getFolder() != null ? book.getFolder() : "";
            args[idx++] = book.getArchiveEntry() != null ? book.getArchiveEntry() : "";
            args[idx++] = book.getLanguage() != null ? book.getLanguage().toString() : null;
            args[idx++] = book.getFileSize();
            args[idx++] = book.getKeywords() != null ? book.getKeywords() : "";
            args[idx++] = book.getAnnotation() != null ? book.getAnnotation() : "";
            args[idx++] = book.getRate();
            args[idx++] = book.getProgress();
            String formattedDate = book.getUpdateDate() != null
                    ? book.getUpdateDate().format(DATE_FORMATTER)
                    : null;
            args[idx++] = formattedDate;
            args[idx++] = book.getIsbn() != null ? book.getIsbn().toString() : null;
            args[idx++] = book.isDeleted() ? 1 : 0;
            args[idx++] = book.isLocal() ? 1 : 0;
            args[idx++] = book.getReview() != null ? book.getReview() : "";
            String formattedCreated = book.getCreatedAt() != null
                    ? book.getCreatedAt().format(DATE_FORMATTER)
                    : LocalDateTime.now().format(DATE_FORMATTER);
            args[idx++] = formattedCreated;
            args[idx++] = book.getCollectionRoot() != null ? book.getCollectionRoot() : "";
            args[idx++] = book.getYear();
            args[idx++] = book.getPublisher() != null ? book.getPublisher() : "";
            args[idx++] = book.getLibId() != null ? book.getLibId() : "";
            args[idx++] = book.getLibraryRate();
            args[idx++] = book.getTranslators() != null ? book.getTranslators() : "";
            args[idx++] = book.getCity() != null ? book.getCity() : "";
            args[idx++] = book.getSourceUrl() != null ? book.getSourceUrl() : "";
            args[idx++] = book.getId().asString(); // WHERE id = ?
            batchArgs.add(args);
        }

        jdbcTemplate.batchUpdate(BookQueries.UPDATE_BOOK, batchArgs);
        log.debug("Batch оновлено {} книг", books.size());
    }
}