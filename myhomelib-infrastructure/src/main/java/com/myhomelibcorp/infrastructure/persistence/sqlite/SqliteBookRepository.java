package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.application.port.out.BookCommandRepository;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@Slf4j
public class SqliteBookRepository implements BookCommandRepository, BookQueryRepository {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final JdbcTemplate jdbcTemplate;
    private final AuthorRepository authorRepository;

    public SqliteBookRepository(JdbcTemplate jdbcTemplate, AuthorRepository authorRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorRepository = authorRepository;
    }

    private final RowMapper<Book> bookRowMapper = (rs, rowNum) -> {
        BookId id = BookId.fromString(rs.getString("id"));

        // Читаємо дату як рядок і парсимо вручну, щоб уникнути проблем із форматом
        LocalDateTime updateDate = null;
        String dateStr = rs.getString("update_date");
        if (dateStr != null && !dateStr.isEmpty()) {
            try {
                updateDate = LocalDateTime.parse(dateStr, DATE_FORMATTER);
            } catch (Exception e) {
                // Якщо не вдалося розпарсити, спробуємо стандартний ISO (з T) – для сумісності
                try {
                    updateDate = LocalDateTime.parse(dateStr);
                } catch (Exception ex) {
                    log.warn("Не вдалося розпарсити дату: {}", dateStr, ex);
                }
            }
        }

        return Book.builder()
                .id(id)
                .title(rs.getString("title"))
                .series(rs.getString("series"))
                .sequenceNumber(rs.getInt("sequence_number"))
                .language(rs.getString("language"))
                .fileName(rs.getString("file_name"))
                .folder(rs.getString("folder"))
                .archiveEntry(rs.getString("archive_entry"))
                .fileSize(rs.getLong("file_size"))
                .keywords(rs.getString("keywords"))
                .annotation(rs.getString("annotation"))
                .rate(rs.getInt("rate"))
                .progress(rs.getInt("progress"))
                .updateDate(updateDate)
                .deleted(rs.getInt("deleted") == 1)
                .local(rs.getInt("local") == 1)
                .build();
    };

    @Override
    @Transactional
    public Book save(Book book) {
        String sql = """
            INSERT OR REPLACE INTO books (
                id, title, series, sequence_number, file_name, folder,
                archive_entry, language, file_size, keywords, annotation,
                rate, progress, update_date, isbn, deleted, local
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            // Форматуємо дату у потрібний формат
            String formattedDate = book.getUpdateDate() != null
                    ? book.getUpdateDate().format(DATE_FORMATTER)
                    : null;
            ps.setString(idx++, formattedDate);
            ps.setString(idx++, book.getIsbn() != null ? book.getIsbn().toString() : null);
            ps.setInt(idx++, book.isDeleted() ? 1 : 0);
            ps.setInt(idx++, book.isLocal() ? 1 : 0);
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
            String insertGenreSql = """
                INSERT OR IGNORE INTO genres (code, name, parent_code, fb2_code)
                VALUES (?, ?, ?, ?)
                """;
            jdbcTemplate.update(insertGenreSql,
                    genre.getId().asString(),
                    genre.getName(),
                    genre.getParentId() != null ? genre.getParentId().asString() : null,
                    genre.getFb2Code());

            jdbcTemplate.update("INSERT OR IGNORE INTO book_genres (book_id, genre_code) VALUES (?, ?)",
                    bookId.asString(), genre.getId().asString());
        }
    }

    private void loadAuthors(Book book) {
        String sql = """
            SELECT a.id, a.first_name, a.middle_name, a.last_name
            FROM authors a
            JOIN book_authors ba ON a.id = ba.author_id
            WHERE ba.book_id = ?
            """;
        List<Author> authors = jdbcTemplate.query(sql, (rs, rowNum) -> {
            AuthorId id = AuthorId.fromString(rs.getString("id"));
            return new Author(id, rs.getString("first_name"), rs.getString("middle_name"), rs.getString("last_name"));
        }, book.getId().asString());
        book.setAuthors(authors);
    }

    private void loadGenres(Book book) {
        String sql = """
            SELECT g.code, g.name, g.parent_code, g.fb2_code
            FROM genres g
            JOIN book_genres bg ON g.code = bg.genre_code
            WHERE bg.book_id = ?
            """;
        List<Genre> genres = jdbcTemplate.query(sql, (rs, rowNum) -> {
            GenreId id = GenreId.fromCode(rs.getString("code"));
            GenreId parentId = rs.getString("parent_code") != null
                    ? GenreId.fromCode(rs.getString("parent_code"))
                    : null;
            return new Genre(id, rs.getString("name"), parentId, rs.getString("fb2_code"));
        }, book.getId().asString());
        book.setGenres(genres);
    }

    @Override
    public void deleteById(BookId id) {
        jdbcTemplate.update("DELETE FROM books WHERE id = ?", id.asString());
        log.debug("Книгу видалено: id={}", id.asString());
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
    public List<Book> findByAuthorId(AuthorId authorId, int limit, int offset) {
        String sql = """
            SELECT b.* FROM books b
            JOIN book_authors ba ON b.id = ba.book_id
            WHERE ba.author_id = ?
            LIMIT ? OFFSET ?
            """;
        List<Book> books = jdbcTemplate.query(sql, bookRowMapper, authorId.asString(), limit, offset);
        books.forEach(this::loadAuthors);
        books.forEach(this::loadGenres);
        return books;
    }

    @Override
    public List<Book> search(String query, int limit) {
        String sql = """
            SELECT b.* FROM books b
            JOIN books_fts f ON b.id = f.book_id
            WHERE books_fts MATCH ?
            LIMIT ?
            """;
        List<Book> books = jdbcTemplate.query(sql, bookRowMapper, query + "*", limit);
        books.forEach(this::loadAuthors);
        books.forEach(this::loadGenres);
        return books;
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

    @Override
    public int getTotalCount() {
        try {
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM books", Integer.class);
        } catch (Exception e) {
            log.warn("Не вдалося отримати кількість книг", e);
            return 0;
        }
    }
}