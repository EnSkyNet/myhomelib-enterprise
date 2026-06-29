package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.application.port.out.GenreService;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.infrastructure.persistence.mapper.GenreRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookGenreHelper {

    private final JdbcTemplate jdbcTemplate;
    private final GenreService genreService;
    private final GenreRowMapper genreRowMapper;

    /**
     * Зберігає жанри для книги.
     * Видаляє старі зв'язки та створює нові.
     */
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

    /**
     * Завантажує жанри для книги.
     */
    public List<Genre> loadGenres(BookId bookId) {
        String sql = """
            SELECT g.code, g.name, g.parent_code, g.fb2_code
            FROM genres g
            JOIN book_genres bg ON g.code = bg.genre_code
            WHERE bg.book_id = ?
            """;
        List<Genre> genres = jdbcTemplate.query(sql, genreRowMapper, bookId.asString());
        log.debug("Завантажено {} жанрів для книги {}", genres.size(), bookId.asString());
        return genres;
    }

    /**
     * Видаляє всі зв'язки жанру з книгами (для видалення жанру).
     */
    public void deleteAllBookLinksForGenre(GenreId genreId) {
        jdbcTemplate.update("DELETE FROM book_genres WHERE genre_code = ?", genreId.asString());
        log.debug("Видалено всі зв'язки для жанру {}", genreId.asString());
    }
}