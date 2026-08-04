package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteBookQueryRepository implements BookQueryRepository {

    private static final int MAX_ALL_FETCH = 10000;
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
            log.info("Завантажено книгу з БД: id={}, title={}, progress={}",
                    id, book.getTitle(), book.getProgress());
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

    /**
     * @deprecated Використовуйте {@link #find(BookQuery)} з пагінацією.
     * Цей метод завантажує максимум {@value #MAX_ALL_FETCH} книг для безпеки.
     */
    @Override
    @Deprecated
    public List<Book> findAll() {
        log.warn("Використання findAll() без пагінації. Обмежено {} записів.", MAX_ALL_FETCH);
        BookQuery query = BookQuery.builder()
                .pagination(Pagination.of(MAX_ALL_FETCH, 0))
                .build();
        return find(query);
    }

    /**
     * Потокове читання всіх книг з пагінацією.
     * Використовує Stream для обробки великих наборів даних.
     * Важливо: Stream потрібно закривати через try-with-resources.
     */
    public Stream<Book> findAllStreaming() {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(
                        new StreamingBookIterator(MAX_ALL_FETCH),
                        Spliterator.ORDERED
                ),
                false
        );
    }

    @Override
    public List<Book> findRecent(int limit) {
        String sql = """
                SELECT id, title, series, file_name, folder, collection_root,
                       language, file_size, rate, progress, update_date, created_at
                FROM books
                ORDER BY update_date DESC
                LIMIT ?
                """;
        List<Book> books = getJdbcTemplate().query(sql, bookRowMapper, limit);
        enrichBooks(books);
        return books;
    }

    @Override
    public List<Book> findRecentlyAdded(int limit) {
        String sql = """
                SELECT id, title, series, file_name, folder, collection_root,
                       language, file_size, rate, progress, update_date, created_at
                FROM books
                ORDER BY created_at DESC
                LIMIT ?
                """;
        List<Book> books = getJdbcTemplate().query(sql, bookRowMapper, limit);
        enrichBooks(books);
        return books;
    }

    @Override
    public List<Book> findFavoriteAuthors(int limit) {
        String sql = """
                SELECT id, title, series, file_name, folder, collection_root,
                       language, file_size, rate, progress, update_date, created_at
                FROM books
                ORDER BY rate DESC
                LIMIT ?
                """;
        List<Book> books = getJdbcTemplate().query(sql, bookRowMapper, limit);
        enrichBooks(books);
        return books;
    }

    @Override
    public long countBooksWithoutAuthor() {
        String sql = """
                SELECT COUNT(*) FROM books b
                WHERE NOT EXISTS (
                    SELECT 1 FROM book_authors ba WHERE ba.book_id = b.id
                )
                """;
        return getJdbcTemplate().queryForObject(sql, Long.class);
    }

    @Override
    public long countBooksWithoutGenre() {
        String sql = """
                SELECT COUNT(*) FROM books b
                WHERE NOT EXISTS (
                    SELECT 1 FROM book_genres bg WHERE bg.book_id = b.id
                )
                """;
        return getJdbcTemplate().queryForObject(sql, Long.class);
    }

    @Override
    public List<BookId> findDuplicateBookIds() {
        String sql = """
                SELECT b.id
                FROM books b
                WHERE EXISTS (
                    SELECT 1
                    FROM books b2
                    JOIN book_authors ba2 ON b2.id = ba2.book_id
                    JOIN authors a2 ON ba2.author_id = a2.id
                    WHERE b2.id != b.id
                      AND b2.title = b.title
                      AND a2.last_name = (
                          SELECT a.last_name
                          FROM book_authors ba
                          JOIN authors a ON ba.author_id = a.id
                          WHERE ba.book_id = b.id
                          LIMIT 1
                      )
                )
                """;
        return getJdbcTemplate().query(sql, (rs, rowNum) -> BookId.fromString(rs.getString("id")));
    }

    // ==================== ВНУТРІШНІЙ КЛАС ІТЕРАТОРА ====================

    private class StreamingBookIterator implements java.util.Iterator<Book> {
        private final int pageSize;
        private int offset = 0;
        private List<Book> currentPage = new ArrayList<>();
        private int currentIndex = 0;
        private boolean finished = false;

        public StreamingBookIterator(int pageSize) {
            this.pageSize = pageSize;
            loadNextPage();
        }

        @Override
        public boolean hasNext() {
            if (finished) return false;
            if (currentIndex < currentPage.size()) return true;
            // Якщо сторінка порожня або менша за pageSize - це кінець
            if (currentPage.size() < pageSize) {
                finished = true;
                return false;
            }
            loadNextPage();
            return !currentPage.isEmpty();
        }

        @Override
        public Book next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            return currentPage.get(currentIndex++);
        }

        private void loadNextPage() {
            BookQuery query = BookQuery.builder()
                    .pagination(Pagination.of(pageSize, offset))
                    .build();
            currentPage = SqliteBookQueryRepository.this.find(query);
            currentIndex = 0;
            offset += pageSize;
            if (currentPage.isEmpty() || currentPage.size() < pageSize) {
                finished = true;
            }
        }
    }
}