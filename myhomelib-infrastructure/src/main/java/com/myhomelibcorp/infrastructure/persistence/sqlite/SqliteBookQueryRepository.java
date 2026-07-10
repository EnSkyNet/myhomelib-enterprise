package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookQueryBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteBookQueryRepository implements BookQueryRepository {

    private final CollectionManager collectionManager;
    private final BookRowMapper bookRowMapper;
    private final BookAuthorHelper bookAuthorHelper;
    private final BookGenreHelper bookGenreHelper;
    private final BookQueryBuilder queryBuilder;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    private void enrichBooks(List<Book> books) {
        if (books.isEmpty()) return;
        bookAuthorHelper.loadAuthorsForBooks(books);
        bookGenreHelper.loadGenresForBooks(books);
    }

    @Override
    public Optional<Book> findById(BookId id) {
        String sql = "SELECT * FROM books WHERE id = ?";
        try {
            Book book = getJdbcTemplate().queryForObject(sql, bookRowMapper, id.asString());
            enrichBooks(List.of(book));
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
        List<Book> books = getJdbcTemplate().query(sql, bookRowMapper, (Object[]) idStrings);
        enrichBooks(books);
        return books;
    }

    @Override
    public List<Book> find(BookQuery query) {
        var sqlQuery = queryBuilder.build(query);
        List<Book> books = getJdbcTemplate().query(sqlQuery.sql(), bookRowMapper, sqlQuery.params());
        enrichBooks(books);
        return books;
    }

    @Override
    public long count(BookQuery query) {
        var sqlQuery = queryBuilder.buildCount(query);
        Long result = getJdbcTemplate().queryForObject(sqlQuery.sql(), Long.class, sqlQuery.params());
        return result != null ? result : 0L;
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
            Book book = getJdbcTemplate().queryForObject(sql, bookRowMapper, title, authorLastName);
            enrichBooks(List.of(book));
            return Optional.of(book);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Book> findAll() {
        String sql = "SELECT * FROM books";
        List<Book> books = getJdbcTemplate().query(sql, bookRowMapper);
        enrichBooks(books);
        return books;
    }

    // === НОВІ МЕТОДИ ДЛЯ DASHBOARD ===

    @Override
    public List<Book> findRecent(int limit) {
        String sql = "SELECT * FROM books ORDER BY update_date DESC LIMIT ?";
        List<Book> books = getJdbcTemplate().query(sql, bookRowMapper, limit);
        enrichBooks(books);
        return books;
    }

    @Override
    public List<Book> findRecentlyAdded(int limit) {
        String sql = "SELECT * FROM books ORDER BY created_at DESC LIMIT ?";
        List<Book> books = getJdbcTemplate().query(sql, bookRowMapper, limit);
        enrichBooks(books);
        return books;
    }

    @Override
    public List<Book> findFavoriteAuthors(int limit) {
        // Використовуємо групу "Favorites" (id = 1) для визначення улюблених авторів
        // Або просто книги з найвищим рейтингом
        String sql = """
            SELECT b.* FROM books b
            ORDER BY b.rate DESC
            LIMIT ?
            """;
        List<Book> books = getJdbcTemplate().query(sql, bookRowMapper, limit);
        enrichBooks(books);
        return books;
    }
}