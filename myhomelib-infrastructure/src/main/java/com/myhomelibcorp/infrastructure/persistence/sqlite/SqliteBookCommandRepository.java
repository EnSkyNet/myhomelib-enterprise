package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.batch.BookBatchWriter;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.query.BookQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteBookCommandRepository implements BookCommandRepository {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final CollectionManager collectionManager;
    private final BookAuthorHelper bookAuthorHelper;
    private final BookGenreHelper bookGenreHelper;
    private final BookBatchWriter batchWriter;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    // ==================== PRAGMA ДЛЯ ШВИДКОГО ІМПОРТУ ====================

    /**
     * Встановлює PRAGMA для максимальної швидкості вставки.
     * Викликати ПЕРЕД великим батчевим імпортом.
     */
    public void setPragmaForBulkInsert() {
        JdbcTemplate jt = getJdbcTemplate();
        jt.execute("PRAGMA synchronous = OFF");
        jt.execute("PRAGMA journal_mode = MEMORY");
        jt.execute("PRAGMA temp_store = MEMORY");
        jt.execute("PRAGMA cache_size = -500000"); // 500 MB
        log.debug("PRAGMA встановлено для швидкого імпорту");
    }

    /**
     * Відновлює стандартні PRAGMA після імпорту.
     */
    public void resetPragma() {
        JdbcTemplate jt = getJdbcTemplate();
        jt.execute("PRAGMA synchronous = NORMAL");
        jt.execute("PRAGMA journal_mode = DELETE");
        log.debug("PRAGMA відновлено до стандартних");
    }

    // ==================== ОСНОВНІ МЕТОДИ ====================

    @Override
    public Book save(Book book) {
        getJdbcTemplate().update(connection -> {
            PreparedStatement ps = connection.prepareStatement(BookQueries.INSERT_OR_UPDATE_BOOK);
            int idx = 1;
            ps.setString(idx++, book.getId().asString());
            ps.setString(idx++, book.getTitle() != null ? book.getTitle() : "");
            ps.setString(idx++, book.getSeries());
            ps.setInt(idx++, book.getSequenceNumber() != null ? book.getSequenceNumber() : 0);
            ps.setString(idx++, book.getFileName() != null ? book.getFileName() : "");
            ps.setString(idx++, book.getFolder());
            ps.setString(idx++, book.getArchiveEntry());
            ps.setString(idx++, book.getLanguage() != null ? book.getLanguage().toString() : null);
            ps.setLong(idx++, book.getFileSize());
            ps.setString(idx++, book.getKeywords() != null ? book.getKeywords() : "");
            ps.setString(idx++, book.getAnnotation() != null ? book.getAnnotation() : "");
            ps.setInt(idx++, book.getRate());
            ps.setInt(idx++, book.getProgress());
            String formattedDate = book.getUpdateDate() != null
                    ? book.getUpdateDate().format(DATE_FORMATTER)
                    : null;
            ps.setString(idx++, formattedDate);
            ps.setString(idx++, book.getIsbn() != null ? book.getIsbn().toString() : null);
            ps.setInt(idx++, book.isDeleted() ? 1 : 0);
            ps.setInt(idx++, book.isLocal() ? 1 : 0);
            ps.setString(idx++, book.getReview() != null ? book.getReview() : "");
            String formattedCreated = book.getCreatedAt() != null
                    ? book.getCreatedAt().format(DATE_FORMATTER)
                    : LocalDateTime.now().format(DATE_FORMATTER);
            ps.setString(idx++, formattedCreated);
            ps.setString(idx++, book.getCollectionRoot() != null ? book.getCollectionRoot() : ""); // нове поле
            return ps;
        });

        if (book.getAuthors() != null && !book.getAuthors().isEmpty()) {
            bookAuthorHelper.saveAuthors(book.getId(), book.getAuthors());
        }
        if (book.getGenres() != null && !book.getGenres().isEmpty()) {
            bookGenreHelper.saveGenres(book.getId(), book.getGenres());
        }

        log.debug("Книгу збережено: id={}, title={}", book.getId().asString(), book.getTitle());
        return book;
    }

    @Override
    public void saveBatch(List<Book> books) {
        if (books == null || books.isEmpty()) return;
        batchWriter.batchInsert(books);
        for (Book book : books) {
            if (book.getAuthors() != null && !book.getAuthors().isEmpty()) {
                bookAuthorHelper.saveAuthors(book.getId(), book.getAuthors());
            }
            if (book.getGenres() != null && !book.getGenres().isEmpty()) {
                bookGenreHelper.saveGenres(book.getId(), book.getGenres());
            }
        }
        log.debug("Batch збережено {} книг", books.size());
    }

    @Override
    public void deleteById(BookId id) {
        getJdbcTemplate().update(BookQueries.DELETE_BY_ID, id.asString());
        log.debug("Книгу видалено: id={}", id.asString());
    }

    @Override
    public void updateRate(BookId bookId, int rate) {
        getJdbcTemplate().update(BookQueries.UPDATE_RATE, rate, bookId.asString());
    }

    @Override
    public void updateProgress(BookId bookId, int progress) {
        getJdbcTemplate().update(BookQueries.UPDATE_PROGRESS, progress, bookId.asString());
    }
}