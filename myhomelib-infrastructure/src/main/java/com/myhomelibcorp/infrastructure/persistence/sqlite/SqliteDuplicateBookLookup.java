package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.DuplicateBookLookup;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class SqliteDuplicateBookLookup implements DuplicateBookLookup {

    private final CollectionManager collectionManager;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public Optional<BookId> findDuplicateId(String title, String firstAuthorLastName) {
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

        try {
            String bookId = getJdbcTemplate().queryForObject(sql, String.class, title, firstAuthorLastName);
            if (bookId != null) {
                return Optional.of(BookId.fromString(bookId));
            }
        } catch (EmptyResultDataAccessException e) {
            // немає результату
        } catch (Exception e) {
            if (isTableMissingError(e)) {
                log.debug("Таблиця 'books' відсутня, вважаємо, що дублікат відсутній");
            } else {
                log.error("Помилка пошуку дубліката", e);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean existsDuplicate(String title, String firstAuthorLastName) {
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
}