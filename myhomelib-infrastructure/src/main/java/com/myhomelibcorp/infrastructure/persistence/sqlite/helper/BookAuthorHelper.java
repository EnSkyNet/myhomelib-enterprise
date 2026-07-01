package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.persistence.mapper.AuthorRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookAuthorHelper {

    private final JdbcTemplate jdbcTemplate;
    private final AuthorRepository authorRepository;
    private final AuthorRowMapper authorRowMapper;

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

    public List<Author> loadAuthors(BookId bookId) {
        String sql = """
            SELECT a.id, a.first_name, a.middle_name, a.last_name
            FROM authors a
            JOIN book_authors ba ON a.id = ba.author_id
            WHERE ba.book_id = ?
            """;
        return jdbcTemplate.query(sql, authorRowMapper, bookId.asString());
    }

    public void loadAuthorsForBooks(List<Book> books) {
        if (books.isEmpty()) return;
        List<String> bookIds = books.stream().map(b -> b.getId().asString()).collect(Collectors.toList());
        String placeholders = String.join(",", bookIds.stream().map(id -> "?").toArray(String[]::new));
        String sql = """
            SELECT b.book_id, a.id, a.first_name, a.middle_name, a.last_name
            FROM book_authors b
            JOIN authors a ON b.author_id = a.id
            WHERE b.book_id IN (""" + placeholders + ")";

        Map<String, List<Author>> authorMap = new HashMap<>();
        jdbcTemplate.query(sql, (rs) -> {
            String bookId = rs.getString("book_id");
            Author author = authorRowMapper.mapRow(rs, 0);
            authorMap.computeIfAbsent(bookId, k -> new ArrayList<>()).add(author);
        }, bookIds.toArray());

        for (Book book : books) {
            List<Author> authors = authorMap.getOrDefault(book.getId().asString(), List.of());
            for (Author author : authors) {
                book.addAuthor(author);
            }
        }
        log.debug("Завантажено авторів для {} книг", books.size());
    }
}