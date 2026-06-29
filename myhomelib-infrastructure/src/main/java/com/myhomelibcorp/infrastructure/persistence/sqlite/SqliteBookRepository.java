package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.application.port.out.BookCommandRepository;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.GenreService;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.infrastructure.persistence.mapper.AuthorRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.GenreRowMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Repository
@Primary
@Slf4j
public class SqliteBookRepository implements BookCommandRepository, BookQueryRepository {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final JdbcTemplate jdbcTemplate;
    private final AuthorRepository authorRepository;
    private final GenreService genreService;

    // Впроваджені мапери (замість внутрішніх)
    private final BookRowMapper bookRowMapper;
    private final AuthorRowMapper authorRowMapper;
    private final GenreRowMapper genreRowMapper;

    public SqliteBookRepository(JdbcTemplate jdbcTemplate,
                                AuthorRepository authorRepository,
                                GenreService genreService,
                                BookRowMapper bookRowMapper,
                                AuthorRowMapper authorRowMapper,
                                GenreRowMapper genreRowMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorRepository = authorRepository;
        this.genreService = genreService;
        this.bookRowMapper = bookRowMapper;
        this.authorRowMapper = authorRowMapper;
        this.genreRowMapper = genreRowMapper;
    }

    // ==================== COMMAND ====================

    @Override
    @Transactional
    public Book save(Book book) {
        String sql = """
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

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
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
            return ps;
        });

        if (book.getAuthors() != null && !book.getAuthors().isEmpty()) {
            saveAuthors(book.getId(), book.getAuthors());
        }

        if (book.getGenres() != null && !book.getGenres().isEmpty()) {
            saveGenres(book.getId(), book.getGenres());
        }

        log.debug("Книгу збережено: id={}, title={}", book.getId().asString(), book.getTitle());
        return book;
    }

    private void saveAuthors(BookId bookId, List<Author> authors) {
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
    }

    private void saveGenres(BookId bookId, List<Genre> genres) {
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
    }

    @Override
    @Transactional
    public void saveBatch(List<Book> books) {
        if (books == null || books.isEmpty()) {
            return;
        }
        String sql = """
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

        List<Object[]> batchArgs = new ArrayList<>(books.size());
        for (Book book : books) {
            Object[] args = new Object[19];
            int idx = 0;
            args[idx++] = book.getId().asString();
            args[idx++] = book.getTitle() != null ? book.getTitle() : "";
            args[idx++] = book.getSeries();
            args[idx++] = book.getSequenceNumber() != null ? book.getSequenceNumber() : 0;
            args[idx++] = book.getFileName() != null ? book.getFileName() : "";
            args[idx++] = book.getFolder();
            args[idx++] = book.getArchiveEntry();
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
            batchArgs.add(args);
        }

        jdbcTemplate.batchUpdate(sql, batchArgs);

        for (Book book : books) {
            if (book.getAuthors() != null && !book.getAuthors().isEmpty()) {
                saveAuthors(book.getId(), book.getAuthors());
            }
            if (book.getGenres() != null && !book.getGenres().isEmpty()) {
                saveGenres(book.getId(), book.getGenres());
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
        String sql = "UPDATE books SET rate = ?, update_date = CURRENT_TIMESTAMP WHERE id = ?";
        jdbcTemplate.update(sql, rate, bookId.asString());
    }

    @Override
    @Transactional
    public void updateProgress(BookId bookId, int progress) {
        String sql = "UPDATE books SET progress = ?, update_date = CURRENT_TIMESTAMP WHERE id = ?";
        jdbcTemplate.update(sql, progress, bookId.asString());
    }

    // ==================== QUERY ====================

    public void loadAuthors(Book book) {
        String sql = """
            SELECT a.id, a.first_name, a.middle_name, a.last_name
            FROM authors a
            JOIN book_authors ba ON a.id = ba.author_id
            WHERE ba.book_id = ?
            """;
        List<Author> authors = jdbcTemplate.query(sql, authorRowMapper, book.getId().asString());
        book.setAuthors(authors);
    }

    public void loadGenres(Book book) {
        String sql = """
            SELECT g.code, g.name, g.parent_code, g.fb2_code
            FROM genres g
            JOIN book_genres bg ON g.code = bg.genre_code
            WHERE bg.book_id = ?
            """;
        List<Genre> genres = jdbcTemplate.query(sql, genreRowMapper, book.getId().asString());
        book.setGenres(genres);
    }

    @Override
    public List<Book> findAll(int limit, int offset) {
        String sql = "SELECT * FROM books LIMIT ? OFFSET ?";
        List<Book> books = jdbcTemplate.query(sql, bookRowMapper, limit, offset);
        books.forEach(this::loadAuthors);
        books.forEach(this::loadGenres);
        return books;
    }

    @Override
    public Optional<Book> findById(BookId id) {
        String sql = "SELECT * FROM books WHERE id = ?";
        try {
            Book book = jdbcTemplate.queryForObject(sql, bookRowMapper, id.asString());
            loadAuthors(book);
            loadGenres(book);
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
        books.forEach(this::loadAuthors);
        books.forEach(this::loadGenres);
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
        books.forEach(this::loadAuthors);
        books.forEach(this::loadGenres);
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
        books.forEach(this::loadAuthors);
        books.forEach(this::loadGenres);
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

        allBooks.forEach(this::loadAuthors);
        allBooks.forEach(this::loadGenres);

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
            loadAuthors(book);
            loadGenres(book);
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
        books.forEach(this::loadAuthors);
        books.forEach(this::loadGenres);
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
        books.forEach(this::loadAuthors);
        books.forEach(this::loadGenres);
        return books;
    }

    public BookRowMapper getBookRowMapper() {
        return bookRowMapper;
    }
}