package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.BookCommandRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteBookCommandRepository implements BookCommandRepository {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final JdbcTemplate jdbcTemplate;
    private final BookAuthorHelper bookAuthorHelper;
    private final BookGenreHelper bookGenreHelper;

    private static final String INSERT_OR_UPDATE_SQL = """
        INSERT INTO books (
            id, title, series, sequence_number, file_name, folder,
            archive_entry, language, file_size, keywords, annotation,
            rate, progress, update_date, isbn, deleted, local,
            review, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
            title = excluded.title,
            series = excluded.series,
            sequence_number = excluded.sequence_number,
            file_name = excluded.file_name,
            folder = excluded.folder,
            archive_entry = excluded.archive_entry,
            language = excluded.language,
            file_size = excluded.file_size,
            keywords = excluded.keywords,
            annotation = excluded.annotation,
            rate = excluded.rate,
            progress = excluded.progress,
            update_date = excluded.update_date,
            isbn = excluded.isbn,
            deleted = excluded.deleted,
            local = excluded.local,
            review = excluded.review,
            created_at = excluded.created_at
        """;

    @Override
    @Transactional
    public Book save(Book book) {
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_OR_UPDATE_SQL);
            int idx = 1;
            ps.setString(idx++, book.getId().asString());
            ps.setString(idx++, book.getTitle() != null ? book.getTitle() : "");
            ps.setString(idx++, book.getSeries());
            ps.setInt(idx++, book.getSequenceNumber() != null ? book.getSequenceNumber() : 0);
            ps.setString(idx++, book.getFile().getFileName() != null ? book.getFile().getFileName() : "");
            ps.setString(idx++, book.getFile().getFolder());
            ps.setString(idx++, book.getFile().getArchiveEntry());
            ps.setString(idx++, book.getMetadata().getLanguage() != null ? book.getMetadata().getLanguage().toString() : null);
            ps.setLong(idx++, book.getFile().getFileSize());
            ps.setString(idx++, book.getMetadata().getKeywords() != null ? book.getMetadata().getKeywords() : "");
            ps.setString(idx++, book.getMetadata().getAnnotation() != null ? book.getMetadata().getAnnotation() : "");
            ps.setInt(idx++, book.getMetadata().getRate());
            ps.setInt(idx++, book.getMetadata().getProgress());
            String formattedDate = book.getUpdateDate() != null
                    ? book.getUpdateDate().format(DATE_FORMATTER)
                    : null;
            ps.setString(idx++, formattedDate);
            ps.setString(idx++, book.getMetadata().getIsbn() != null ? book.getMetadata().getIsbn().toString() : null);
            ps.setInt(idx++, book.isDeleted() ? 1 : 0);
            ps.setInt(idx++, book.isLocal() ? 1 : 0);
            ps.setString(idx++, book.getMetadata().getReview() != null ? book.getMetadata().getReview() : "");
            String formattedCreated = book.getCreatedAt() != null
                    ? book.getCreatedAt().format(DATE_FORMATTER)
                    : LocalDateTime.now().format(DATE_FORMATTER);
            ps.setString(idx++, formattedCreated);
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
    @Transactional
    public void saveBatch(List<Book> books) {
        if (books == null || books.isEmpty()) return;

        List<Object[]> batchArgs = new ArrayList<>(books.size());
        for (Book book : books) {
            Object[] args = new Object[19];
            int idx = 0;
            args[idx++] = book.getId().asString();
            args[idx++] = book.getTitle() != null ? book.getTitle() : "";
            args[idx++] = book.getSeries();
            args[idx++] = book.getSequenceNumber() != null ? book.getSequenceNumber() : 0;
            args[idx++] = book.getFile().getFileName() != null ? book.getFile().getFileName() : "";
            args[idx++] = book.getFile().getFolder();
            args[idx++] = book.getFile().getArchiveEntry();
            args[idx++] = book.getMetadata().getLanguage() != null ? book.getMetadata().getLanguage().toString() : null;
            args[idx++] = book.getFile().getFileSize();
            args[idx++] = book.getMetadata().getKeywords() != null ? book.getMetadata().getKeywords() : "";
            args[idx++] = book.getMetadata().getAnnotation() != null ? book.getMetadata().getAnnotation() : "";
            args[idx++] = book.getMetadata().getRate();
            args[idx++] = book.getMetadata().getProgress();
            String formattedDate = book.getUpdateDate() != null
                    ? book.getUpdateDate().format(DATE_FORMATTER)
                    : null;
            args[idx++] = formattedDate;
            args[idx++] = book.getMetadata().getIsbn() != null ? book.getMetadata().getIsbn().toString() : null;
            args[idx++] = book.isDeleted() ? 1 : 0;
            args[idx++] = book.isLocal() ? 1 : 0;
            args[idx++] = book.getMetadata().getReview() != null ? book.getMetadata().getReview() : "";
            String formattedCreated = book.getCreatedAt() != null
                    ? book.getCreatedAt().format(DATE_FORMATTER)
                    : LocalDateTime.now().format(DATE_FORMATTER);
            args[idx++] = formattedCreated;
            batchArgs.add(args);
        }

        jdbcTemplate.batchUpdate(INSERT_OR_UPDATE_SQL, batchArgs);

        for (Book book : books) {
            if (book.getAuthors() != null && !book.getAuthors().isEmpty()) {
                bookAuthorHelper.saveAuthors(book.getId(), book.getAuthors());
            }
            if (book.getGenres() != null && !book.getGenres().isEmpty()) {
                bookGenreHelper.saveGenres(book.getId(), book.getGenres());
            }
        }

        log.debug("Збережено батч: {} книг", books.size());
    }

    @Override
    public void deleteById(BookId id) {
        jdbcTemplate.update("DELETE FROM books WHERE id = ?", id.asString());
        log.debug("Книгу видалено: id={}", id.asString());
    }

    @Override
    @Transactional
    public void updateRate(BookId bookId, int rate) {
        jdbcTemplate.update("UPDATE books SET rate = ?, update_date = CURRENT_TIMESTAMP WHERE id = ?", rate, bookId.asString());
    }

    @Override
    @Transactional
    public void updateProgress(BookId bookId, int progress) {
        jdbcTemplate.update("UPDATE books SET progress = ?, update_date = CURRENT_TIMESTAMP WHERE id = ?", progress, bookId.asString());
    }
}