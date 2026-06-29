package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.persistence.mapper.AuthorRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookAuthorHelper {

    private final JdbcTemplate jdbcTemplate;
    private final AuthorRepository authorRepository;
    private final AuthorRowMapper authorRowMapper;

    /**
     * Зберігає авторів для книги.
     * Видаляє старі зв'язки та створює нові.
     */
    public void saveAuthors(BookId bookId, List<Author> authors) {
        jdbcTemplate.update("DELETE FROM book_authors WHERE book_id = ?", bookId.asString());

        for (Author author : authors) {
            Author existing = authorRepository.findByFullName(author.getFirstName(), author.getLastName())
                    .orElse(null);
            if (existing != null) {
                author = existing;
            } else {
                author = authorRepository.save(author);
            }
            jdbcTemplate.update("INSERT OR IGNORE INTO book_authors (book_id, author_id) VALUES (?, ?)",
                    bookId.asString(), author.getId().asString());
        }
        log.debug("Збережено {} авторів для книги {}", authors.size(), bookId.asString());
    }

    /**
     * Завантажує авторів для книги.
     */
    public List<Author> loadAuthors(BookId bookId) {
        String sql = """
            SELECT a.id, a.first_name, a.middle_name, a.last_name
            FROM authors a
            JOIN book_authors ba ON a.id = ba.author_id
            WHERE ba.book_id = ?
            """;
        List<Author> authors = jdbcTemplate.query(sql, authorRowMapper, bookId.asString());
        log.debug("Завантажено {} авторів для книги {}", authors.size(), bookId.asString());
        return authors;
    }

    /**
     * Видаляє всі зв'язки автора з книгами (для видалення автора).
     */
    public void deleteAllBookLinksForAuthor(AuthorId authorId) {
        jdbcTemplate.update("DELETE FROM book_authors WHERE author_id = ?", authorId.asString());
        log.debug("Видалено всі зв'язки для автора {}", authorId.asString());
    }
}