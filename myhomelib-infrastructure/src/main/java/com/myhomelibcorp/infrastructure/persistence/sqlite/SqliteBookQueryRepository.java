package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteBookQueryRepository implements BookQueryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final BookRowMapper bookRowMapper;
    private final BookAuthorHelper bookAuthorHelper;
    private final BookGenreHelper bookGenreHelper;

    /**
     * Додає авторів і жанри до книги.
     * Використовує addAuthor/addGenre, оскільки Book immutable.
     */
    private void enrichBook(Book book) {
        if (book == null) return;
        List<Author> authors = bookAuthorHelper.loadAuthors(book.getId());
        for (Author author : authors) {
            book.addAuthor(author);
        }
        List<Genre> genres = bookGenreHelper.loadGenres(book.getId());
        for (Genre genre : genres) {
            book.addGenre(genre);
        }
    }

    @Override
    public List<Book> findAll(int limit, int offset) {
        String sql = "SELECT * FROM books LIMIT ? OFFSET ?";
        List<Book> books = jdbcTemplate.query(sql, bookRowMapper, limit, offset);
        books.forEach(this::enrichBook);
        return books;
    }

    @Override
    public Optional<Book> findById(BookId id) {
        String sql = "SELECT * FROM books WHERE id = ?";
        try {
            Book book = jdbcTemplate.queryForObject(sql, bookRowMapper, id.asString());
            enrichBook(book);
            return Optional.of(book);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Book> findByIds(List<BookId> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toArray(String[]::new));
        String sql = "SELECT * FROM books WHERE id IN (" + placeholders + ")";
        String[] idStrings = ids.stream().map(BookId::asString).toArray(String[]::new);
        List<Book> books = jdbcTemplate.query(sql, bookRowMapper, (Object[]) idStrings);
        books.forEach(this::enrichBook);
        return books;
    }

    @Override
    public List<Book> findByAuthorId(AuthorId authorId, int limit, int offset) {
        log.info("🔍 findByAuthorId: authorId={}, limit={}, offset={}", authorId.asString(), limit, offset);
        String sql = """
        SELECT b.* FROM books b
        JOIN book_authors ba ON b.id = ba.book_id
        WHERE ba.author_id = ?
        LIMIT ? OFFSET ?
        """;
        List<Book> books = jdbcTemplate.query(sql, bookRowMapper, authorId.asString(), limit, offset);
        log.info("📚 Знайдено {} книг для автора {}", books.size(), authorId.asString());
        books.forEach(this::enrichBook);
        return books;
    }

    @Override
    public List<Book> search(String query, int limit) {
        String sql = """
            SELECT * FROM books
            WHERE lower(title) LIKE ?
               OR lower(series) LIKE ?
               OR lower(keywords) LIKE ?
               OR lower(annotation) LIKE ?
            LIMIT ?
            """;
        String pattern = "%" + (query != null ? query.toLowerCase() : "") + "%";
        List<Book> books = jdbcTemplate.query(sql, bookRowMapper, pattern, pattern, pattern, pattern, limit);
        books.forEach(this::enrichBook);
        return books;
    }

    @Override
    public List<Book> searchByAuthor(String authorName, int limit) {
        if (authorName == null || authorName.isBlank()) {
            return List.of();
        }

        String pattern = authorName.trim().toLowerCase(Locale.ROOT);
        log.debug("Пошук за автором (SQL fallback): pattern='{}'", pattern);

        String sql = "SELECT DISTINCT b.* FROM books b";
        List<Book> allBooks = jdbcTemplate.query(sql, bookRowMapper);
        allBooks.forEach(this::enrichBook);

        return allBooks.stream()
                .filter(book -> book.getAuthors().stream()
                        .anyMatch(author -> {
                            String fullName = Stream.of(
                                            author.getLastName(),
                                            author.getFirstName(),
                                            author.getMiddleName())
                                    .filter(Objects::nonNull)
                                    .map(String::trim)
                                    .filter(s -> !s.isEmpty())
                                    .collect(Collectors.joining(" "))
                                    .toLowerCase(Locale.ROOT);
                            return fullName.contains(pattern);
                        }))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Book> findByTitleAndAuthor(String title, String authorLastName) {
        String sql = """
            SELECT b.* FROM books b
            JOIN book_authors ba ON b.id = ba.book_id
            JOIN authors a ON ba.author_id = a.id
            WHERE b.title = ? AND a.last_name = ?
            LIMIT 1
            """;
        try {
            Book book = jdbcTemplate.queryForObject(sql, bookRowMapper, title, authorLastName);
            enrichBook(book);
            return Optional.of(book);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public int getTotalCount() {
        try {
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM books", Integer.class);
        } catch (Exception e) {
            log.warn("Не вдалося отримати кількість книг", e);
            return 0;
        }
    }

    @Override
    public List<Book> findBySeries(String seriesName, int limit, int offset) {
        if (seriesName == null || seriesName.isBlank()) {
            return List.of();
        }
        String sql = "SELECT * FROM books WHERE series = ? ORDER BY sequence_number LIMIT ? OFFSET ?";
        List<Book> books = jdbcTemplate.query(sql, bookRowMapper, seriesName, limit, offset);
        books.forEach(this::enrichBook);
        return books;
    }

    @Override
    public List<Book> findByGenre(String genreCode, int limit, int offset) {
        if (genreCode == null || genreCode.isBlank()) {
            return List.of();
        }
        String sql = """
        SELECT b.* FROM books b
        JOIN book_genres bg ON b.id = bg.book_id
        WHERE bg.genre_code = ?
        ORDER BY b.title
        LIMIT ? OFFSET ?
        """;
        List<Book> books = jdbcTemplate.query(sql, bookRowMapper, genreCode, limit, offset);
        books.forEach(this::enrichBook);
        return books;
    }
}