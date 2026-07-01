package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.application.port.out.GenreService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.infrastructure.persistence.mapper.GenreRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookGenreHelper {

    private final JdbcTemplate jdbcTemplate;
    private final GenreService genreService;
    private final GenreRowMapper genreRowMapper;

    public void saveGenres(BookId bookId, List<Genre> genres) {
        jdbcTemplate.update("DELETE FROM book_genres WHERE book_id = ?", bookId.asString());
        for (Genre genre : genres) {
            String code = genre.getId().asString();
            String name = genreService.getGenreName(code);
            String insertGenreSql = """
                INSERT INTO genres (code, name, parent_code, fb2_code)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(code) DO UPDATE SET
                    name = excluded.name,
                    parent_code = excluded.parent_code,
                    fb2_code = excluded.fb2_code
                """;
            jdbcTemplate.update(insertGenreSql,
                    code,
                    name,
                    genre.getParentId() != null ? genre.getParentId().asString() : null,
                    genre.getFb2Code());

            jdbcTemplate.update("INSERT OR IGNORE INTO book_genres (book_id, genre_code) VALUES (?, ?)",
                    bookId.asString(), code);
        }
        log.debug("Збережено {} жанрів для книги {}", genres.size(), bookId.asString());
    }

    public List<Genre> loadGenres(BookId bookId) {
        String sql = """
            SELECT g.code, g.name, g.parent_code, g.fb2_code
            FROM genres g
            JOIN book_genres bg ON g.code = bg.genre_code
            WHERE bg.book_id = ?
            """;
        return jdbcTemplate.query(sql, genreRowMapper, bookId.asString());
    }

    public void loadGenresForBooks(List<Book> books) {
        if (books.isEmpty()) return;
        List<String> bookIds = books.stream().map(b -> b.getId().asString()).collect(Collectors.toList());
        String placeholders = String.join(",", bookIds.stream().map(id -> "?").toArray(String[]::new));
        String sql = """
            SELECT bg.book_id, g.code, g.name, g.parent_code, g.fb2_code
            FROM book_genres bg
            JOIN genres g ON bg.genre_code = g.code
            WHERE bg.book_id IN (""" + placeholders + ")";

        Map<String, List<Genre>> genreMap = new HashMap<>();
        jdbcTemplate.query(sql, (rs) -> {
            String bookId = rs.getString("book_id");
            Genre genre = genreRowMapper.mapRow(rs, 0);
            genreMap.computeIfAbsent(bookId, k -> new ArrayList<>()).add(genre);
        }, bookIds.toArray());

        for (Book book : books) {
            List<Genre> genres = genreMap.getOrDefault(book.getId().asString(), List.of());
            for (Genre genre : genres) {
                book.addGenre(genre);
            }
        }
        log.debug("Завантажено жанрів для {} книг", books.size());
    }
}