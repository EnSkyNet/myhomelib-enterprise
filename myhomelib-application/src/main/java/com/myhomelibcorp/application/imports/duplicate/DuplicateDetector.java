package com.myhomelibcorp.application.imports.duplicate;

import com.myhomelibcorp.application.port.out.infrastructure.JdbcTemplateProvider;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class DuplicateDetector {

    private final JdbcTemplateProvider jdbcTemplateProvider;
    private final BookQueryRepository bookQueryRepository;

    private final Set<String> batchKeyCache = new HashSet<>();
    private static final int MAX_CACHE_SIZE = 10_000;

    private JdbcTemplate getJdbcTemplate() {
        return jdbcTemplateProvider.getCurrentJdbcTemplate();
    }

    public boolean isDuplicate(Book book) {
        if (book == null || book.getAuthors().isEmpty()) {
            return false;
        }

        String key = buildNaturalKey(book);
        if (batchKeyCache.contains(key)) {
            return true;
        }

        String sql = """
                SELECT EXISTS (
                    SELECT 1 FROM books b
                    WHERE b.title = ?
                      AND EXISTS (
                          SELECT 1 FROM book_authors ba
                          JOIN authors a ON ba.author_id = a.id
                          WHERE ba.book_id = b.id
                            AND a.last_name = ?
                      )
                )
                """;

        String title = book.getTitle();
        String firstAuthorLastName = book.getAuthors().get(0).getLastName();

        try {
            Boolean exists = getJdbcTemplate().queryForObject(sql, Boolean.class, title, firstAuthorLastName);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            if (isTableMissingError(e)) {
                log.debug("Таблиця 'books' відсутня, вважаємо, що дублікат відсутній");
            } else {
                log.error("Помилка перевірки дубліката", e);
            }
            return false;
        }
    }

    private boolean isTableMissingError(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SQLException sqlEx) {
                String msg = sqlEx.getMessage();
                if (msg != null && msg.contains("no such table")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    public Optional<Book> findDuplicate(Book book) {
        if (book == null || book.getAuthors().isEmpty()) {
            return Optional.empty();
        }

        String sql = """
                SELECT b.id FROM books b
                WHERE b.title = ?
                  AND EXISTS (
                      SELECT 1 FROM book_authors ba
                      JOIN authors a ON ba.author_id = a.id
                      WHERE ba.book_id = b.id
                        AND a.last_name = ?
                  )
                LIMIT 1
                """;

        String title = book.getTitle();
        String firstAuthorLastName = book.getAuthors().get(0).getLastName();

        try {
            String bookId = getJdbcTemplate().queryForObject(sql, String.class, title, firstAuthorLastName);
            if (bookId != null) {
                return bookQueryRepository.findById(BookId.fromString(bookId));
            }
        } catch (Exception e) {
            log.debug("Дублікат не знайдено для книги: {}", title);
        }
        return Optional.empty();
    }

    public void addKey(Book book) {
        if (book == null) return;
        String key = buildNaturalKey(book);
        batchKeyCache.add(key);

        if (batchKeyCache.size() > MAX_CACHE_SIZE) {
            log.debug("Кеш дублікатів перевищив {} записів, очищення", MAX_CACHE_SIZE);
            batchKeyCache.clear();
        }
    }

    public void addAllKeys(List<Book> books) {
        if (books == null || books.isEmpty()) return;
        for (Book book : books) {
            batchKeyCache.add(buildNaturalKey(book));
        }
        if (batchKeyCache.size() > MAX_CACHE_SIZE) {
            batchKeyCache.clear();
        }
    }

    public void clearCache() {
        batchKeyCache.clear();
        log.debug("Кеш дублікатів очищено");
    }

    private String buildNaturalKey(Book book) {
        String firstAuthor = book.getAuthors().stream()
                .findFirst()
                .map(Author::getLastName)
                .orElse("");
        return (book.getTitle() + "|" + firstAuthor).toLowerCase().trim();
    }
}